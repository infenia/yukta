#!/usr/bin/env bash
# Shared helpers for `yk` and the dogfood scripts. Sourced, not executed.

REPO_ROOT="${YUKTA_REPO_ROOT:-$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)}"
# Per checkout: heal records, durations, check logs, hook marks.
STATE_DIR="${YUKTA_DEV_STATE_DIR:-$REPO_ROOT/.yukta-dev}"
# Per machine: the one dev server every checkout shares (main worktree, builds by commit, pid, log, sessions).
DEV_HOME="${YUKTA_DEV_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/yukta-dev}"
DEV_PORT="${YUKTA_DEV_PORT:-9858}"
DEV_URL="http://127.0.0.1:$DEV_PORT"

die() { echo "error: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not on PATH"; }

os_arch() {
  local os arch
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  case "$(uname -m)" in
    x86_64 | amd64) arch=amd64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) arch="$(uname -m)" ;;
  esac
  echo "$os-$arch"
}

# dev_key: the dev server's API key (generated once, readable only by this user). Without a key the
# server's REST API is open to every local account, and it runs any process (YUKTA_PROCESS_ALLOW_ANY).
dev_key() {
  local file="$DEV_HOME/api-key"
  if [[ ! -s "$file" ]]; then
    mkdir -p "$DEV_HOME"
    (umask 077 && head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' >"$file")
  fi
  cat "$file"
}

# use_dev_key: hands the key to the yukta CLI (YUKTA_TOKEN) when it talks to the dev server.
use_dev_key() {
  if [[ -z "${YUKTA_TOKEN:-}" && "${YUKTA_SERVER_URL:-$DEV_URL}" == "$DEV_URL" ]]; then
    YUKTA_TOKEN="$(dev_key)"
    export YUKTA_TOKEN
  fi
}

# server_env KEY: a value from the running dev server's state file, or nothing.
server_env() { sed -n "s/^$1=//p" "$DEV_HOME/server.env" 2>/dev/null; }

# Locate the yukta CLI: $YUKTA_BIN (e.g. a build of your branch), the CLI built with the running
# server, the newest main build, PATH, then this checkout's cli/build.
find_cli() {
  local f
  if [[ -n "${YUKTA_BIN:-}" ]]; then echo "$YUKTA_BIN"; return; fi
  f="$(server_env YUKTA_DEV_CLI)"
  [[ -n "$f" && -x "$f" ]] && { echo "$f"; return; }
  f="$(ls -t "$DEV_HOME"/builds/*/yukta-cli 2>/dev/null | head -1)"
  [[ -n "$f" && -x "$f" ]] && { echo "$f"; return; }
  if command -v yukta-cli >/dev/null 2>&1; then command -v yukta-cli; return; fi
  f="$(ls -t "$REPO_ROOT/cli/build/yukta-cli-$(os_arch)" "$REPO_ROOT/cli/build/yukta-cli" 2>/dev/null | head -1)"
  [[ -n "$f" && -x "$f" ]] && { echo "$f"; return; }
  die "yukta-cli not found; run 'yk up' (builds it from main) or set YUKTA_BIN"
}

# render_session TEMPLATE PROJECT_PATH: prints the session JSON with __PROJECT_PATH__ replaced.
# Done on parsed JSON so any characters in the path are escaped correctly.
render_session() {
  jq --arg p "$2" 'walk(if type == "string" then split("__PROJECT_PATH__") | join($p) else . end)' "$1"
}

# scope_to_modules MODULE...: reads session JSON on stdin, rewrites the gradle-check workflow to run
# only the tasks of MODULEs (gradle paths without the leading colon, e.g. core or
# plugins:processors:decision). Plain tasks get a ":MODULE:" prefix per module; tasks pinned to another
# module and the root-only trivy are dropped; stages left empty disappear and, the workflow being a
# linear chain, the edges are rebuilt. cli is a Go module: only its explicit :cli: tasks apply.
scope_to_modules() {
  jq '
    $ARGS.positional as $mods
    | def scoped($cmd):
        [$mods[] as $m | $cmd[1:][] | select(startswith("-") | not)
          | if startswith(":") then select(startswith(":" + $m + ":"))
            elif . == "trivy" or $m == "cli" then empty
            else ":" + $m + ":" + . end]
        | (reduce .[] as $t ([]; if index([$t]) then . else . + [$t] end)) as $t
        | if ($t | length) == 0 then null
          else [$cmd[0]] + $t + [$cmd[1:][] | select(startswith("-"))] end;
    (.workflows["gradle-check"], (.workflows["format"] | select(. != null))) |= (
      .nodes |= [.[] | if .type == "PROCESS_EXECUTOR"
          then (scoped(.config.command) as $c | if $c == null then empty else .config.command = $c end)
          else . end]
      | .edges = [range(1; .nodes | length) as $i
          | {source: .nodes[$i - 1].nodeId, target: .nodes[$i].nodeId}])' --args -- "$@"
}

# splice_args ARGS...: reads session JSON on stdin, replaces the "__GRADLE_ARGS__" element of the
# gradle-run command with ARGS, each kept as its own argv entry so spaces survive.
splice_args() {
  jq '.workflows["gradle-run"].nodes |= map(if .config.command
        then .config.command |= [.[] | if . == "__GRADLE_ARGS__" then $ARGS.positional[] else . end]
        else . end)' --args -- "$@"
}

# changes_sum ROOT: a hash of the uncommitted code changes of a checkout (the Stop hook skips a check
# that already passed on the same hash)
changes_sum() {
  (git -C "$1" diff HEAD; git -C "$1" status --porcelain -- '*.java' '*.kts' '*.gradle' '*.go' '*.ts' '*.tsx' '*.json') 2>/dev/null \
    | sha1sum | cut -d' ' -f1
}

# ---- The dev server: built from origin/main, one per machine, on $DEV_PORT ----

healthy() { curl -fsS -m 3 "${1:-$DEV_URL}/actuator/health" >/dev/null 2>&1; }

# busy: the number of executions not yet ended, on any session of the dev server. When that cannot be
# read (the server answers badly, or not at all) it says 1: never stop a server we cannot see idle.
busy() {
  local n
  n="$(curl -fsS -m 5 -H "X-API-Key: $(dev_key)" "$DEV_URL/api/executions?limit=1000" 2>/dev/null \
    | jq -e '[(.data // .)[] | select(.status | test("RUNNING|PENDING|QUEUED|PAUSED"))] | length' 2>/dev/null)"
  [[ "$n" =~ ^[0-9]+$ ]] && echo "$n" || echo 1
}

# main_sync: brings the managed worktree $DEV_HOME/main to origin/main (or $YUKTA_DEV_MAIN_REF) and
# prints its commit. The worktree belongs to yk: local edits in it are discarded.
main_sync() {
  local wt="$DEV_HOME/main" ref="${YUKTA_DEV_MAIN_REF:-origin/main}"
  if [[ ! -e "$wt/.git" ]]; then
    git -C "$REPO_ROOT" fetch -q origin main || echo "warning: fetch failed; using the local origin/main" >&2
    git -C "$REPO_ROOT" worktree add -q --detach "$wt" "$ref" >&2
  else
    git -C "$wt" fetch -q origin main || echo "warning: fetch failed; using the local origin/main" >&2
    git -C "$wt" checkout -q --force --detach "$ref" >&2
  fi
  git -C "$wt" rev-parse HEAD
}

# main_build SHA: builds the server jar and the CLI of the main worktree into $DEV_HOME/builds/SHA,
# unless that build exists. Keeps the three newest builds.
main_build() {
  local sha="$1" wt="$DEV_HOME/main" out="$DEV_HOME/builds/$1" log="$DEV_HOME/build.log" src d jar
  [[ -s "$out/yukta.jar" && -x "$out/yukta-cli" ]] && return 0
  # The ui build needs the node/pnpm toolchain gradle downloaded into ui/.gradle; reuse a checkout's copy.
  for src in "$REPO_ROOT/ui/.gradle" "$(dirname "$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir)")/ui/.gradle"; do
    if [[ -d "$src/pnpm" ]]; then
      mkdir -p "$wt/ui/.gradle"
      for d in "$src"/*; do [[ -e "$wt/ui/.gradle/${d##*/}" ]] || cp -r --reflink=auto "$d" "$wt/ui/.gradle/"; done
      break
    fi
  done
  echo "building main@${sha:0:9} (bootJar + CLI; minutes on a new commit, log: $log) ..." >&2
  (cd "$wt" && ./gradlew :boot:bootJar :cli:goBuild -q --console=plain) >"$log" 2>&1 \
    || die "build of main@${sha:0:9} failed; see $log"
  jar="$(ls -t "$wt"/boot/build/libs/*.jar 2>/dev/null | grep -v plain | head -1)"
  [[ -n "$jar" ]] || die "no boot jar after the build; see $log"
  rm -rf "$out.tmp" && mkdir -p "$out.tmp"
  cp "$jar" "$out.tmp/yukta.jar"
  cp "$wt/cli/build/yukta-cli-$(os_arch)" "$out.tmp/yukta-cli"
  mv "$out.tmp" "$out"
  ls -dt "$DEV_HOME"/builds/*/ | tail -n +4 | xargs -r rm -rf --
}

# server_launch KIND ID LABEL RULES CMD...: starts the dev server in its own process group (so
# server_stop stops it with its children) and records it in $DEV_HOME/server.env.
server_launch() {
  local kind="$1" id="$2" label="$3" rules="$4" cli
  shift 4
  mkdir -p "$DEV_HOME/sessions"
  rm -f "$DEV_HOME/server.pid"
  (
    cd "$DEV_HOME"
    export SERVER_PORT="$DEV_PORT" YUKTA_SERVER_ADDRESS=127.0.0.1 YUKTA_PROCESS_ALLOW_ANY=true \
      YUKTA_CONCURRENCY_GROUPS_GRADLE="${YUKTA_DEV_GRADLE_PARALLEL:-2}" \
      YUKTA_DEV_HOME="$DEV_HOME" YUKTA_SESSION_BASE_DIR="$DEV_HOME/sessions" YUKTA_SESSION_EXECUTION_TIMEOUT_SECONDS=86400 \
      YUKTA_SUPERVISOR_RULES_FILE="$rules"
    # The key goes in a file, not the environment: the server's children (the gradle stages and their
    # test JVMs) inherit its environment, and a Spring Boot test that saw YUKTA_API_KEY would demand it.
    (umask 077 && printf 'yukta.api-key=%s\n' "$(dev_key)" >"$DEV_HOME/server.properties")
    setsid bash -c 'echo $$ >"$1"; shift; exec "$@"' _ "$DEV_HOME/server.pid" "$@" \
      "--spring.config.additional-location=file:$DEV_HOME/server.properties" \
      >"$DEV_HOME/server.log" 2>&1 </dev/null 9>&- &
  )
  for _ in 1 2 3 4 5; do [[ -s "$DEV_HOME/server.pid" ]] && break; sleep 1; done
  cli="$DEV_HOME/builds/$id/yukta-cli"
  [[ -x "$cli" ]] || cli="$(ls -t "$DEV_HOME"/builds/*/yukta-cli 2>/dev/null | head -1)"
  cat >"$DEV_HOME/server.env" <<ENV
YUKTA_DEV_KIND=$kind
YUKTA_DEV_SHA=$id
YUKTA_DEV_LABEL=$label
YUKTA_DEV_CLI=$cli
YUKTA_DEV_KEYED=2
YUKTA_SERVER_URL=$DEV_URL
ENV
  echo "waiting for Yukta at $DEV_URL (log: $DEV_HOME/server.log) ..." >&2
  for _ in $(seq 1 60); do
    kill -0 "$(cat "$DEV_HOME/server.pid" 2>/dev/null)" 2>/dev/null \
      || { rm -f "$DEV_HOME/server.env"; die "server exited early; see $DEV_HOME/server.log"; }
    if healthy; then
      echo "ready: $DEV_URL ($label)"
      return 0
    fi
    sleep 2
  done
  die "server did not become healthy in 120s; see $DEV_HOME/server.log"
}

# server_stop: stops the server yk started (whole process group). Never touches anything else.
server_stop() {
  local pid
  pid="$(cat "$DEV_HOME/server.pid" 2>/dev/null)" || return 0
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 -- "-$pid" 2>/dev/null; then
    kill -TERM -- "-$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 -- "-$pid" 2>/dev/null || break
      sleep 1
    done
    kill -KILL -- "-$pid" 2>/dev/null || true
  fi
  rm -f "$DEV_HOME/server.pid" "$DEV_HOME/server.env"
}

# ours: the process on the dev port is the server yk started.
ours() { [[ -s "$DEV_HOME/server.pid" ]] && kill -0 "$(cat "$DEV_HOME/server.pid")" 2>/dev/null; }

# server_up [--release [TAG]]: makes the dev server run the current origin/main (or a release). A
# no-op when it already does; when it is stale and busy the swap is left for a later call.
server_up() {
  local kind=main tag="" id label cmd rules running
  if [[ "${1:-}" == --release ]]; then kind=release tag="${2:-}"; fi
  need git; need curl; need jq; need flock
  mkdir -p "$DEV_HOME"
  exec 9>"$DEV_HOME/up.lock"
  flock 9
  if [[ "$kind" == release ]]; then
    local bin
    bin="$("$REPO_ROOT/examples/11-dogfood/scripts/yukta-release.sh" ${tag:+--version "$tag"})" || exit 1
    tag="$(basename "$(dirname "$(dirname "$bin")")")"
    id="release-$tag" label="release $tag" cmd=("$bin") rules="$REPO_ROOT/examples/11-dogfood/rules.json"
  else
    need java
    id="$(main_sync)"
    main_build "$id"
    label="main@${id:0:9}" cmd=(java -jar "$DEV_HOME/builds/$id/yukta.jar")
    rules="$DEV_HOME/main/examples/11-dogfood/rules.json"
  fi
  if healthy; then
    ours || die "port $DEV_PORT is used by a server yk did not start; stop it or set YUKTA_DEV_PORT"
    running="$(server_env YUKTA_DEV_SHA)"
    # a server started before yk set an API key is replaced like a stale one
    if [[ "$running" == "$id" && "$(server_env YUKTA_DEV_KEYED)" == 2 ]]; then
      echo "ready: $DEV_URL ($label)"
      return 0
    fi
    local n
    n="$(busy)"
    if ((n > 0)); then
      echo "STALE: the server runs $(server_env YUKTA_DEV_LABEL), $label is built; swap pending ($n executions running)"
      return 0
    fi
    echo "swapping $(server_env YUKTA_DEV_LABEL) for $label" >&2
    server_stop
  elif (exec 3<>"/dev/tcp/127.0.0.1/$DEV_PORT") 2>/dev/null; then
    die "port $DEV_PORT is in use by another program; free it or set YUKTA_DEV_PORT"
  fi
  server_launch "$kind" "$id" "$label" "$rules" "${cmd[@]}"
}

# ensure_server: before a run. Starts the dev server if it is down, and swaps an idle server for an
# already built newer main (no fetch, no build: `yk up` and the SessionStart hook do those).
# YUKTA_SERVER_URL pointing elsewhere opts out: that server is used as it is.
ensure_server() {
  if [[ -n "${YUKTA_SERVER_URL:-}" && "$YUKTA_SERVER_URL" != "$DEV_URL" ]]; then return 0; fi
  export YUKTA_SERVER_URL="$DEV_URL"
  if ! healthy; then
    echo ">> no dev server on $DEV_URL; starting one (yk up)" >&2
    (server_up) >&2 || exit 1
    return 0
  fi
  ours && [[ "$(server_env YUKTA_DEV_KIND)" == main ]] || return 0
  local sha
  sha="$(git -C "$DEV_HOME/main" rev-parse "${YUKTA_DEV_MAIN_REF:-origin/main}" 2>/dev/null)" || return 0
  if [[ ("$sha" != "$(server_env YUKTA_DEV_SHA)" || "$(server_env YUKTA_DEV_KEYED)" != 2) \
    && -s "$DEV_HOME/builds/$sha/yukta.jar" && "$(busy)" == 0 ]]; then
    (server_up) >&2 || true
  fi
}

# ---- The self-healing check ----

# heal_extras POLICY MODULES: prints, as a JSON array of stage nodes, the extra stages (ui, docs, reuse,
# actionlint) that apply: those whose module is in scope (all of them unscoped, except ui and docs only
# with their module) and whose tool is installed. A missing tool is a WARN on stderr, not a failure.
heal_extras() {
  local name tool command nodes="[]"
  # YUKTA_CHECK_EXTRAS=off: only the gradle stages (the online test of the graph runs without the tools)
  [[ "${YUKTA_CHECK_EXTRAS:-}" == off ]] && { echo "[]"; return; }
  for name in $(jq -r '.extra // {} | keys[]' <<<"$1"); do
    jq -e --arg n "$name" --arg mods "$2" '.extra[$n] as $e | ($mods | split(" ") | map(select(. != ""))) as $m
      | ($m | length) == 0 or (($e.modules // []) | any(. as $x | $m | index($x)))' <<<"$1" >/dev/null || continue
    command=""
    for tool in $(jq -r --arg n "$name" '.extra[$n].variants[].tool' <<<"$1"); do
      command -v "$tool" >/dev/null 2>&1 || continue
      command="$(jq -c --arg n "$name" --arg t "$tool" '.extra[$n].variants[] | select(.tool == $t) | .command' <<<"$1")"
      break
    done
    if [[ -z "$command" ]]; then
      echo "WARN: stage $name skipped, none of: $(jq -r --arg n "$name" '[.extra[$n].variants[].tool] | join(", ")' <<<"$1") is installed" >&2
      continue
    fi
    nodes="$(jq -c --arg n "$name" --argjson c "$command" '. + [{nodeId: $n, type: "PROCESS_EXECUTOR", config: {command: $c}}]' <<<"$nodes")"
  done
  echo "$nodes"
}

# heal_events_file: where heal's monitoring events go: YUKTA_HEAL_EVENTS, else heal-events.jsonl in the dev state dir; "off" or an empty
# YUKTA_HEAL_EVENTS turns it off (prints nothing). One append-only file per machine: it outlives the per-run heal dirs.
heal_events_file() {
  local f="${YUKTA_HEAL_EVENTS-$DEV_HOME/heal-events.jsonl}"
  [[ "$f" == off ]] && f=""
  printf '%s' "$f"
}

# heal_workflow POLICY MODULES PROJECT HEALSH HEALDIR [EXTRAS [WORKFLOW [ISSUESTATE]]]: reads session JSON on stdin and adds the
# workflow "check", the gradle-check stages unrolled into ONE graph, so a run is one execution that
# `yk watch` follows and `yk retry` can steer. POLICY is heal.json (which stages are analyzers, and per stage
# {auto, claude}). ISSUESTATE is where an issue run keeps its records (a start variable); heal and fix read the
# implementer's cost there to keep to the budget of the issue.
#
# A sequential stage S (spotless, compile, tests, coverage) runs, and a failure goes to the deterministic fix
# where there is one (spotless: S.f1, then S.run2), then to the `heal` workflow (S.heal, a SUB_WORKFLOW the stage
# waits for; see heal_subflows):
#   S.run1 --failure--> [S.f1 --success--> S.run2 --failure-->] S.heal --> next stage
#          \--success--> next stage                    (a failed heal fails the run: failed_node=S)
# Analyzers (style, spotbugs, ...) run in parallel after the last sequential stage; those with a policy go
# through a join, a gate and ONE analyzers.heal, the others (licenses, security) fail the run at once.
# verify re-runs everything if anything was healed.
heal_workflow() {
  # true when any analyzer of the join failed (an analyzer is skipped by the gate when none did)
  local gate="payload['items'].?[['success'] == false].size() > 0"
  jq --arg gate "$gate" --arg events "$(heal_events_file)" --argjson extras "${6:-[]}" --arg wfid "${7:-check}" --arg issuestate "${8:--}" --argjson policy "$1" --arg modules "$2" --arg project "$3" --arg healsh "$4" --arg healdir "$5" '
    ($modules | split(" ") | map(select(. != ""))) as $mods
    | def px: . as $t | if ($mods | length) > 0 and ($t | startswith(":") | not)
        then $mods[] | select(. != "cli") | ":" + . + ":" + $t else $t end;   # cli (Go) has no gradle fix tasks
    def gradle_tasks($n): [$n.config.command[1:][] | select(startswith("-") | not)] | join(" ");
    def healable($n): ($policy.stages[$n.nodeId] // {}).claude == true;
    # YUKTA_CLAUDE_BIN swaps the claude binary of the fixers (test-self-heal.sh uses a fake one)
    def env($tasks): {YUKTA_HEAL_DIR: $healdir, YUKTA_HEAL_TASKS: $tasks, YUKTA_MODULE: $modules}
        + (if $events != "" then {YUKTA_HEAL_EVENTS: $events} else {} end)
        + (if $ENV.YUKTA_CLAUDE_BIN then {YUKTA_CLAUDE_BIN: $ENV.YUKTA_CLAUDE_BIN} else {} end);
    def pe($id; $cfg): {nodeId: $id, type: "PROCESS_EXECUTOR",
        config: ({workingDir: $project, timeout: 3600, timeoutSeconds: 3600} + $cfg)};
    def routed($on): if $on then {failureMode: "CONTINUE", routeByExitCode: true} else {failureMode: "ERROR"} end;
    # gradle nodes share the server-wide group "gradle" (yukta.concurrency.groups.gradle): the parallel analyzers
    # wait QUEUED instead of each starting a 4 GB daemon
    def grouped($n): if $n.config.command[0] == "./gradlew" then {engine: {concurrencyGroup: "gradle"}} else {} end;
    def runner($id; $n; $on): pe($id; {command: ([$healsh, "run", $n.nodeId, "--"] + $n.config.command), env: env("")} + grouped($n) + routed($on));
    def autofix($id; $stage; $tasks):
        pe($id; {command: [$healsh, "fix", $stage, "1", "auto"], env: env($tasks)} + routed(true));
    def heal_node($s; $tasks; $analyzers): {nodeId: ($s + ".heal"), type: "SUB_WORKFLOW",
        config: {workflowId: "heal", timeoutSeconds: 14400,
                 payload: {project: $project, stage: $s, tasks: (if $tasks == "" then "-" else $tasks end), healDir: $healdir,
                           modules: (if $modules == "" then "-" else $modules end), analyzers: $analyzers, issueState: $issuestate}}};
    def edge($s; $t; $port): {source: $s, target: $t} + (if $port == null then {} else {sourcePort: $port} end);
    def id($n; $pre; $i): $n + "." + $pre + ($i | tostring);

    # a sequential stage S that hands a failure to `heal`: [auto fix, rerun,] heal. Its exits are what the next stage hangs on
    def seq($n; $in):
        ($policy.stages[$n.nodeId] // {}) as $p | $n.nodeId as $s
        | (if (($p.auto // []) | length) > 0 then 1 else 0 end) as $a
        | (if healable($n) then 1 else 0 end) as $h
        | (1 + $a) as $r
        | def routed_run($j): $j < $r or $h == 1;
        {nodes: ([range(1; $r + 1) as $j | runner(id($s; "run"; $j); $n; routed_run($j))]
                 + [range(0; $a) as $i | autofix(id($s; "f"; $i + 1); $s; (($p.auto // []) | map(px) | join(" ")))]
                 + (if $h == 1 then [heal_node($s; gradle_tasks($n); "-")] else [] end)),
         edges: ([$in[] | edge(.source; id($s; "run"; 1); .port)]
                 + [range(1; $r + 1) as $j | select(routed_run($j))
                    | edge(id($s; "run"; $j); (if $j <= $a then id($s; "f"; $j) else id($s; "heal"; "") end); "failure")]
                 + [range(0; $a) as $i
                    | edge(id($s; "f"; $i + 1); id($s; "run"; $i + 2); "success"),
                      edge(id($s; "f"; $i + 1); (if $h == 1 then id($s; "heal"; "") else id($s; "run"; $i + 2) end); "failure")]),
         exits: ([range(1; $r + 1) as $j | select(routed_run($j)) | {source: id($s; "run"; $j), port: "success"}]
                 + (if $h == 1 then [{source: id($s; "heal"; ""), port: null}] else [{source: id($s; "run"; $r), port: null}] end))};

    .workflows["gradle-check"].nodes as $all
    | [$all[] | select(.type == "PROCESS_EXECUTOR")] as $stages
    | [$stages[] | select(.nodeId as $x | $policy.analyzers | index($x) | not)] as $sequential
    | ([$stages[] | select(.nodeId as $x | $policy.analyzers | index($x))] + $extras) as $analyzers
    | [$analyzers[] | select(healable(.))] as $healable
    | (reduce $sequential[] as $n ({nodes: [{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}}], edges: [],
                                    exits: [{source: "trigger", port: null}]};
          seq($n; .exits) as $s
          | {nodes: (.nodes + $s.nodes), edges: (.edges + $s.edges), exits: $s.exits})) as $head
    # analyzers: parallel, joined; the gate and the one heal only when an analyzer can be healed
    | (if ($analyzers | length) == 0 then $head
       else
         ([$healable[] | .nodeId + "=" + (if .config.command[0] == "./gradlew" then (.config.command | join(" ")) else (.config.command | @sh) end)]
           | join("\n")) as $list
         | {nodes: ($head.nodes
                    + [$analyzers[] | runner(.nodeId; .; healable(.))]
                    + [{nodeId: "analyzers.join", type: "AGGREGATE",
                        # the join idles while the slowest analyzer runs: as long as an analyzer may
                        config: {size: ($analyzers | length), timeoutSeconds: 3600}}]),
            edges: ($head.edges
                    + [$analyzers[] | .nodeId as $a | $head.exits[] | edge(.source; $a; .port)]
                    + [$analyzers[] | .nodeId as $a
                       | if healable(.)
                         then edge($a; "analyzers.join"; "success"), edge($a; "analyzers.join"; "failure")
                         else edge($a; "analyzers.join"; null) end]),
            exits: [{source: "analyzers.join", port: null}]}
         | if ($healable | length) == 0 then .
           else
             .nodes += [{nodeId: "analyzers.gate", type: "BRANCH",
                         config: {mode: "EXPRESSION", defaultPort: "pass", cases: {($gate): "fail"}}},
                        heal_node("analyzers"; ""; $list)]
             | .edges += [edge("analyzers.join"; "analyzers.gate"; null), edge("analyzers.gate"; "analyzers.heal"; "fail")]
             | .exits = [{source: "analyzers.gate", port: "pass"}, {source: "analyzers.heal", port: null}]
           end
       end) as $body
    | ([$stages[] | select(.config.command[0] == "./gradlew") | .config.command[1:][] | select(startswith("-") | not)]
       | unique | join(" ")) as $everything
    | del(.workflows["gradle-check"])
    | .workflows[$wfid] = {workflowId: $wfid, description: "The quality gate with self-healing, one execution",
        nodes: ($body.nodes
                + [pe("verify"; {command: [$healsh, "verify"], env: env($everything)} + routed(false)),
                   {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}}]),
        edges: ($body.edges + [$body.exits[] | edge(.source; "verify"; .port)] + [edge("verify"; "done"; null)])}'
}

# heal_subflows POLICY HEALSH [PAUSES]: the workflows a failing stage of `check` hands over to, as {workflows: {...}}.
#   heal            brief -> triage (System One, CLASSIFY) -> fix at the start rung chosen from the difficulty,
#                   then the next model at low effort, until the last model fails (the run fails then):
#     triage --<level>--> fix-<m>/<e> --failure--> fix-<next m>/low --failure--> ... --failure--> giveup
#     any fix --success--> done
#   fix-from-<e>    one model, effort climbing from <e>: fix@e -> recheck -> (fail) fix@next -> recheck ... -> done;
#                   the last recheck fails the run. Model, stage and the rest come from the start payload, and each
#                   fixer is time-boxed and budgeted by its effort (heal.json "efforts").
# YUKTA_TRIAGE=off leaves the triage out: heal then starts at the policy's default level.
# --pause before-opus: a WAIT_FOR_SIGNAL in front of every opus fixer, as in the unrolled chain.
heal_subflows() {
  jq -n --argjson policy "$1" --arg healsh "$2" --arg pauses "${3:-}" --arg triage "${YUKTA_TRIAGE:-on}" --arg events "$(heal_events_file)" \
    --arg pausewait "${YUKTA_PAUSE_SECONDS:-600}" '
    ($policy.models) as $models
    | ($policy.efforts | keys_unsorted) as $efforts
    | $policy.triage as $tri
    | def env($extra): {YUKTA_HEAL_DIR: "${start.healDir}", YUKTA_HEAL_TASKS: "${start.tasks}",
                        YUKTA_MODULE: "${start.modules}", YUKTA_HEAL_ANALYZERS: "${start.analyzers}",
                        # an issue run: what the implementer spent counts against the budget of the issue, and its nav.json
                        # (next to the cost file) gives the fixers the same code index
                        YUKTA_IMPL_COSTFILE: "${start.issueState}/implement.json"}
        + (if $events != "" then {YUKTA_HEAL_EVENTS: $events} else {} end)
        + (if $ENV.YUKTA_CLAUDE_BIN then {YUKTA_CLAUDE_BIN: $ENV.YUKTA_CLAUDE_BIN} else {} end)
        + (if $ENV.YUKTA_BUDGET_ISSUE then {YUKTA_BUDGET_ISSUE: $ENV.YUKTA_BUDGET_ISSUE} else {} end) + $extra;
      def pe($id; $cfg): {nodeId: $id, type: "PROCESS_EXECUTOR",
          config: ({workingDir: "${start.project}", timeout: 3600, timeoutSeconds: 3600} + $cfg)};
      def routed($on): if $on then {failureMode: "CONTINUE", routeByExitCode: true} else {failureMode: "ERROR"} end;
      def edge($s; $t; $port): {source: $s, target: $t} + (if $port == null then {} else {sourcePort: $port} end);
      def rung($m; $e): "fix-" + $m + "-" + $e;
      def paused($m): ($pauses | split(",") | index("before-opus")) != null and $m == "opus";
      def pause_id($m): "pause-" + $m;
      # where a failure that wants model $m goes: its pause, or its low rung
      def enter($m): if paused($m) then pause_id($m) else rung($m; "low") end;
      def fixwf($from):
        ($efforts | index($from)) as $i | $efforts[$i:] as $es | ($es | length) as $n
        | {workflowId: ("fix-from-" + $from),
           description: "One model at rising effort, the failing check re-run after every attempt",
           nodes: ([{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}}]
                   + [range(0; $n) as $j | $es[$j] as $e | $policy.efforts[$e] as $c
                      | pe("fix." + $e; {command: [$healsh, "fix", "${start.stage}", "0", "${start.model}", "--effort", $e],
                                         timeout: $c.timeoutSeconds, timeoutSeconds: $c.timeoutSeconds,
                                         env: env({YUKTA_HEAL_BUDGET: ($c.budget | tostring), YUKTA_HEAL_TIMEOUT: ($c.timeoutSeconds | tostring)})}
                                        + routed(true)),
                        pe("check." + $e; {command: [$healsh, "recheck", "${start.stage}"], env: env({})} + routed($j < $n - 1))]
                   + [{nodeId: "done", type: "CONSOLE_TERMINAL", config: {}}]),
           edges: ([edge("trigger"; "fix." + $es[0]; null)]
                   + [range(0; $n) as $j | $es[$j] as $e
                      | edge("fix." + $e; "check." + $e; "success"),
                        edge("fix." + $e; (if $j < $n - 1 then "fix." + $es[$j + 1] else "check." + $e end); "failure"),
                        (if $j < $n - 1 then edge("check." + $e; "fix." + $es[$j + 1]; "failure") else empty end),
                        edge("check." + $e; "done"; (if $j < $n - 1 then "success" else null end))])};
      def level_start($l): $tri.levels[$l].start | split("/") | {model: .[0], effort: .[1]};
      # the SUB_WORKFLOW node of one rung
      def fixnode($m; $e):
        {nodeId: rung($m; $e), type: "SUB_WORKFLOW",
         config: {workflowId: ("fix-from-" + $e), timeoutSeconds: 14400, routeByOutcome: true,
                  payload: {project: "${start.project}", stage: "${start.stage}", tasks: "${start.tasks}", healDir: "${start.healDir}",
                            modules: "${start.modules}", analyzers: "${start.analyzers}", issueState: "${start.issueState}", model: $m}}};
      # only the rungs a failure can reach (a node nothing leads to is refused): the starts, and each later model at low
      (if $triage == "off" then [$tri.levels[$tri.default].start] else [$tri.levels[] | .start] end
        | map(split("/")) | map({model: .[0], effort: .[1]})) as $starts
      | ($starts + [$starts[] | .model as $m | $models[($models | index($m)) + 1:][] | {model: ., effort: "low"}]) as $all
      | ($all | unique_by([.model, .effort])) as $rungs
      | ([$models[] | select(paused(.)) | select(. as $m | $rungs | any(.model == $m and .effort == "low"))]) as $pausedmodels
      | {workflows: ({heal: {workflowId: "heal", description: "Triage a failed check, then fix it with the cheapest model that can",
          nodes: ([{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}},
                   pe("brief"; {command: [$healsh, "brief", "${start.stage}"], env: env({})} + routed(false))]
                  + (if $triage == "off" then [] else
                      [{nodeId: "triage", type: "CLASSIFY",
                        config: {model: $tri.model, state: "payload.stdout", routeByLabel: true, minProbability: $tri.minProbability, timeout: ($tri.timeoutSeconds // 55),
                                 # tests point it at a stand-in for Ollama
                                 baseUrl: ($ENV.YUKTA_TRIAGE_URL // "http://localhost:11434"),
                                 question: ($tri.question // "How hard is it to fix this failed check of a Java build?"),
                                 labels: ($tri.levels | with_entries({key, value: .value.describe})),
                                 engine: {onError: "ROUTE"}}}] end)
                  + [$rungs[] | fixnode(.model; .effort)]
                  + [$pausedmodels[] | {nodeId: pause_id(.), type: "WAIT_FOR_SIGNAL",
                                        config: {signal: "before-opus", timeoutSeconds: ($pausewait | tonumber), onTimeout: "CONTINUE"}}]
                  # how the triage ended is logged by a node per way it can end (the message of the classification arrives as
                  # YUKTA_METADATA_* variables); each is a second target of the ports of the triage, so it never decides where the heal goes
                  + (if $triage == "off" then [] else
                      [("level", "uncertain", "error") | {nodeId: ("triage-log-" + .), type: "PROCESS_EXECUTOR",
                         config: {command: [$healsh, "triage-log", "${start.stage}", .], workingDir: "${start.project}", inputMode: "ENV",
                                  failureMode: "CONTINUE", timeout: 60, timeoutSeconds: 60, env: env({})}}]
                      + [{nodeId: "triage-sink", type: "CONSOLE_TERMINAL", config: {}}] end)
                  + [{nodeId: "giveup", type: "PROCESS_EXECUTOR",
                      config: {command: [$healsh, "giveup", "${start.stage}"], workingDir: "${start.project}", failureMode: "ERROR",
                               env: env({})}},
                     {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}}]),
          edges: ([edge("trigger"; "brief"; null), edge("giveup"; "done"; null)] + (if $triage == "off"
                     then [edge("brief"; rung(level_start($tri.default).model; level_start($tri.default).effort); null)]
                     else [edge("brief"; "triage"; null)]
                          + [$tri.levels | keys_unsorted[] | . as $l | level_start($l) as $s
                             | edge("triage"; (if paused($s.model) and $s.effort == "low" then pause_id($s.model) else rung($s.model; $s.effort) end); $l)]
                          + [edge("triage"; rung(level_start($tri.default).model; level_start($tri.default).effort); "uncertain"),
                             edge("triage"; rung(level_start($tri.default).model; level_start($tri.default).effort); "error")]
                          + [$tri.levels | keys_unsorted[] | edge("triage"; "triage-log-level"; .)]
                          + [edge("triage"; "triage-log-uncertain"; "uncertain"), edge("triage"; "triage-log-error"; "error"),
                             edge("triage-log-level"; "triage-sink"; null), edge("triage-log-uncertain"; "triage-sink"; null),
                             edge("triage-log-error"; "triage-sink"; null)] end)
                  + [$pausedmodels[] | edge(pause_id(.); rung(.; "low"); null)]
                  + [$rungs[] | . as $r
                     | edge(rung($r.model; $r.effort); "done"; "success"),
                       (($models | index($r.model)) as $mi
                        | edge(rung($r.model; $r.effort); (if $mi + 1 < ($models | length) then enter($models[$mi + 1]) else "giveup" end); "failure"))])}}
        + ([$efforts[] | . as $e | {key: ("fix-from-" + $e), value: fixwf($e)}] | from_entries))}'
}

# policy_models POLICY MODELS: the policy limited to the comma separated MODELS (yk check --models), in the order of
# the policy; a triage level that starts at a model left out starts at the first model kept, at the same effort.
policy_models() {
  [[ -z "$2" ]] && { echo "$1"; return; }
  jq --arg m "$2" '($m | split(",")) as $keep | ([.models[] | select(. as $x | $keep | index($x))]) as $models
    | .models = $models
    | .triage.levels |= map_values(.start |= (split("/") | (if (.[0] as $x | $models | index($x)) then . else [$models[0], .[1]] end) | join("/")))' <<<"$1"
}

# check_stages: reads session JSON on stdin and prints the nodes of the check workflow, one per line.
check_nodes() { jq -r '.workflows.check.nodes[].nodeId'; }

# node_stage NODE: the stage a node of the check belongs to (S.run2 and S.f1 belong to S).
node_stage() { sed -E 's/\.(run[0-9]+|f[0-9]+|r[0-9]+|pause|heal)$//' <<<"$1"; }

# emit_result SESSION EXECUTION STATUS FAILED HEALED PROGRESS: the `result` event of the monitoring log: how the run ended, its wall time,
# and the seconds each node took (a node reports its own start, so a stage is the sum of its nodes: S.run1, S.f1, S.heal ...).
emit_result() {
  local events project
  events="$(heal_events_file)"
  [[ -n "$events" ]] || return 0
  project="${YUKTA_PROJECT:-$REPO_ROOT}"
  mkdir -p "$(dirname "$events")" 2>/dev/null || return 0
  jq -nc --arg session "$1" --arg run "$2" --arg status "$3" --arg failed "$4" --arg healed "$5" --arg scope "${YUKTA_SCOPE:-all}" \
    --arg project "$(basename "$project")" --arg sha "$(git -C "$project" rev-parse --short=9 HEAD 2>/dev/null || echo unknown)" \
    --arg branch "$(git -C "$project" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)" --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson progress "$6" '
    # whole seconds plus the fraction the server prints, so a short stage is not rounded to nothing
    def secs: ((.[:19] | strptime("%Y-%m-%dT%H:%M:%S") | mktime) + ((.[19:] | capture("^\\.(?<f>[0-9]+)") | "0." + .f | tonumber) // 0));
    def stage: sub("\\.(run[0-9]+|f[0-9]+|r[0-9]+|pause|heal)$"; "");
    def r1: (. * 10 | round) / 10;
    [$progress.tasks[] | select((.startTime // "0001")[:4] != "0001" and (.endTime // "0001")[:4] != "0001")
     | {node: .nodeId, s: (.startTime | secs), e: (.endTime | secs)}] as $t
    | {v: 1, ts: $ts, run: $run, session: $session, project: $project, event: "result", workflow: ($progress.workflowId // ""), status: $status, failed_node: $failed,
       healed: $healed, scope: $scope, sha: $sha, branch: $branch,
       secs: (if ($t | length) > 0 then ((([$t[].e] | max) - ([$t[].s] | min)) | r1) else null end),
       durations: ($t | group_by(.node | stage) | map({key: (.[0].node | stage), value: (map(.e - .s) | add | r1)}) | from_entries)}' 2>/dev/null \
    | { flock 9; cat >>"$events"; } 9>>"$events.lock" 2>/dev/null || true
}

# print_result SESSION EXECUTION: prints the machine readable RESULT line; returns 0 only when the
# execution ended SUCCESS.
print_result() {
  local progress status failed healed outcome
  progress="$("$(find_cli)" -o json workflow status "$1" "$2" | jq '(.data // .)')"
  status="$(jq -r '.status' <<<"$progress")"
  failed="$(jq -r '[.tasks[] | select(.status == "FAILURE" or .status == "TIMED_OUT") | .nodeId][0] // "-"' <<<"$progress")"
  if [[ "$failed" != - ]]; then
    failed="$(node_stage "$failed")"
    # the analyzers share one fixer chain; name the ones still failing
    if [[ "$failed" == analyzers && -s "$STATE_DIR/heal/$1/analyzers.failed" ]]; then
      failed="$(cat "$STATE_DIR/heal/$1/analyzers.failed")"
    fi
  fi
  healed="$(healed_stages "$1" "$failed")"
  # a run can end well and still not be done: an OUTCOME_TERMINAL that reached ESCALATE, FAILED or RETRY
  outcome="$(jq -r '[.tasks[] | select(.module == "OUTCOME_TERMINAL" and .metadata.reached == true) | .metadata.outcome][0] // "-"' <<<"$progress")"
  [[ "$status" == SUCCESS && "$outcome" != - && "$outcome" != SUCCESS ]] && status="$outcome"
  emit_result "$1" "$2" "$status" "$failed" "${healed:--}" "$progress"
  echo "RESULT status=$status failed_node=$failed healed=${healed:--} execution=$2 session=$1"
  # what the status line and the Stop hook read: the changes this result is for, and how it ended
  mkdir -p "$STATE_DIR"
  printf '%s\t%s\t%s\t%s\t%s\n' "$(changes_sum "${YUKTA_PROJECT:-$REPO_ROOT}")" "$status" "${YUKTA_SCOPE:--}" "${healed:--}" "$(date +%s)" \
    >"$STATE_DIR/last-result"
  [[ "$status" == COMPLETED || "$status" == SUCCESS ]]
}

# healed_stages SESSION [FAILED_STAGE]: "stage:step,..." for every heal step of the last check run
# that fixed its stage (records written by yukta-heal.sh, step = auto or the model), except the stage
# that still failed; empty when nothing was healed.
healed_stages() {
  local dir="${YUKTA_HEAL_RECORDS:-$STATE_DIR/heal/$1}"
  [[ -d "$dir" ]] || return 0
  jq -rs --arg failed "${2:--}" '[.[] | select(.fixed and .stage != $failed) | "\(.stage):\(.mode)\(if .effort then "/" + .effort else "" end)"] | unique | join(",")' \
    "$dir"/*-[0-9]*.json 2>/dev/null || true
}

# record_durations SESSION EXECUTION SCOPE: appends each finished node's duration to
# $STATE_DIR/durations.tsv (date, workflow:scope, stage, seconds, execution) and prints
#   SLOW <stage> <s>s (median <m>s, <n> runs)
# for a stage that took over 15s and 1.5x the median of its last 10 runs at the same workflow and
# scope (at least 3 earlier runs). SCOPE is the module, "all" or the gradle args of the run. A node
# reports its own start (its first input), so the seconds of a stage are the sum of its nodes
# (S.run1, S.f1, S.run2, ...); parallel analyzers are timed one by one.
# ponytail: whole-second resolution, and a plain median (failed fast runs count); use a percentile if noisy.
record_durations() {
  local session="$1" exec="$2" scope="$3" file="$STATE_DIR/durations.tsv"
  local wf stage secs key prior n median
  mkdir -p "$STATE_DIR"
  touch "$file"
  while IFS=$'\t' read -r wf stage secs; do
    key="$wf:$scope"
    prior="$(awk -F'\t' -v k="$key" -v n="$stage" '$2 == k && $3 == n { print $4 }' "$file" | tail -10 | sort -n)"
    n="$(grep -c . <<<"$prior" || true)"
    if ((n >= 3 && secs > 15)); then
      median="$(awk '{ a[NR] = $1 } END { print a[int((NR + 1) / 2)] }' <<<"$prior")"
      if ((secs * 2 > median * 3)); then
        echo "SLOW $stage ${secs}s (median ${median}s, $n runs)"
      fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$(date -Is)" "$key" "$stage" "$secs" "$exec" >>"$file"
  done < <("$(find_cli)" -o json workflow status "$session" "$exec" | jq -r '
    def secs: .[:19] | strptime("%Y-%m-%dT%H:%M:%S") | mktime;
    def stage: sub("\\.(run[0-9]+|f[0-9]+|r[0-9]+|pause|heal)$"; "");
    (.data // .) as $p
    | [$p.tasks[] | select((.startTime // "0001")[:4] != "0001" and (.endTime // "0001")[:4] != "0001")
       | {stage: (.nodeId | stage), secs: ((.endTime | secs) - (.startTime | secs))}]
    | group_by(.stage)[] | "\($p.workflowId)\t\(.[0].stage)\t\(map(.secs) | add)"')
}

# ---- The issue workflows ----

# issue_variant IMPL_PAUSE PR_PAUSE: the suffix of the workflows without the checkpoints that are off (0)
issue_variant() {
  if [[ "$1" == 1 && "$2" == 1 ]]; then echo ""
  elif [[ "$1" == 0 && "$2" == 1 ]]; then echo "-no-impl-pause"
  elif [[ "$1" == 1 && "$2" == 0 ]]; then echo "-no-pr-pause"
  else echo "-no-pauses"; fi
}

# issue_workflows ISSUESH IMPL_PAUSE PR_PAUSE: prints {"workflows": {...}} with run-issue and implement-issue
# (suffixed by issue_variant). Everything varying per run comes from the start payload: issue, repo (the
# main checkout), worktree.
#
#   run-issue        the outer run: implement-issue as a sub-workflow; however it ends badly (a failing node,
#                    a rejected or unanswered checkpoint, a failing check) release gives the issue back to Ready
#                    and the run ends ESCALATE, the worktree kept
#   implement-issue  claim -> worktree -> brief -> [before-implement] -> nav
#                    (a code-navigation index of the worktree) -> implement -> check (the self-healing gate: triage,
#                    fresh fixers) -> [before-pr] -> pr -> done
issue_workflows() {
  local suffix
  suffix="$(issue_variant "$2" "$3")"
  jq -n --arg sh "$1" --arg impl "$2" --arg pr "$3" --arg suffix "$suffix" '
    def env: {YUKTA_ISSUE: "${start.issue}", YUKTA_REPO_DIR: "${start.repo}", YUKTA_WORKTREE: "${start.worktree}"}
             + (if $ENV.YUKTA_CLAUDE_BIN then {YUKTA_CLAUDE_BIN: $ENV.YUKTA_CLAUDE_BIN} else {} end)
             + (if $ENV.YUKTA_GH then {YUKTA_GH: $ENV.YUKTA_GH} else {} end)
             + (if $ENV.YUKTA_WARM_DELAY then {YUKTA_WARM_DELAY: $ENV.YUKTA_WARM_DELAY} else {} end)
             + (if $ENV.YUKTA_BUDGET_ISSUE then {YUKTA_BUDGET_ISSUE: $ENV.YUKTA_BUDGET_ISSUE} else {} end)
             + (if $ENV.YUKTA_BUDGET_EPIC then {YUKTA_BUDGET_EPIC: $ENV.YUKTA_BUDGET_EPIC} else {} end)
             + (if $ENV.YUKTA_NAV_BACKENDS then {YUKTA_NAV_BACKENDS: $ENV.YUKTA_NAV_BACKENDS} else {} end)
             + (if $ENV.YUKTA_NAV_IDE then {YUKTA_NAV_IDE: $ENV.YUKTA_NAV_IDE} else {} end)
             + (if $ENV.YUKTA_NAV_IDE_URL then {YUKTA_NAV_IDE_URL: $ENV.YUKTA_NAV_IDE_URL} else {} end)
             + (if $ENV.YUKTA_NAV_CBM_BIN then {YUKTA_NAV_CBM_BIN: $ENV.YUKTA_NAV_CBM_BIN} else {} end)
             + (if $ENV.YUKTA_NAV_CBM_CACHE then {YUKTA_NAV_CBM_CACHE: $ENV.YUKTA_NAV_CBM_CACHE} else {} end)
             + (if $ENV.YUKTA_NAV_INDEX_TIMEOUT then {YUKTA_NAV_INDEX_TIMEOUT: $ENV.YUKTA_NAV_INDEX_TIMEOUT} else {} end)
             + (if $ENV.YUKTA_PROJECT_STATUS_CMD then {YUKTA_PROJECT_STATUS_CMD: $ENV.YUKTA_PROJECT_STATUS_CMD} else {} end);
    def step($id; $action; $secs; $extra):
      {nodeId: $id, type: "PROCESS_EXECUTOR",
       config: {command: [$sh, $action], workingDir: "${start.repo}", timeout: $secs, timeoutSeconds: $secs,
                failureMode: "ERROR", env: (env + $extra)}};
    def pause($id; $signal; $secs; $onTimeout):
      {nodeId: $id, type: "WAIT_FOR_SIGNAL", config: {signal: $signal, timeoutSeconds: $secs, onTimeout: $onTimeout}};
    def edge($s; $t): {source: $s, target: $t};
    def chain($ids): [range(1; $ids | length) as $i | edge($ids[$i - 1]; $ids[$i])];
    def runner($wid; $inner; $desc):
      {workflowId: $wid, description: $desc,
       nodes: [{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}},
               {nodeId: "issue", type: "SUB_WORKFLOW",
                config: {workflowId: $inner, timeoutSeconds: 28800, routeByOutcome: true,
                         payload: {issue: "${start.issue}", repo: "${start.repo}", worktree: "${start.worktree}"}}},
               {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}},
               (step("release"; "release"; 600; {}) | .config.failureMode = "CONTINUE"),
               {nodeId: "escalate", type: "OUTCOME_TERMINAL",
                config: {outcome: "ESCALATE", reason: "the issue run did not finish: the issue is Ready again, the worktree is kept"}}],
       edges: [edge("trigger"; "issue"),
               {source: "issue", target: "done", sourcePort: "success"},
               {source: "issue", target: "release", sourcePort: "failure"},
               {source: "issue", target: "release", sourcePort: "escalate"},
               {source: "issue", target: "release", sourcePort: "retry"},
               edge("release"; "escalate")]};
    ([{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}},
      step("claim"; "claim"; 600; {}),
      step("worktree"; "worktree"; 1800; {}),
      step("brief"; "brief"; 600; {})]
     + (if $impl == "1" then [pause("before-implement"; "before-implement"; 3600; "CONTINUE")] else [] end)
     + [step("nav"; "nav"; 3600; {}),
        step("implement"; "implement"; 14400; {}),
        {nodeId: "check", type: "SUB_WORKFLOW",
         config: {workflowId: "check", timeoutSeconds: 14400,
                  payload: {project: "${start.worktree}", issueState: "${start.repo}/.yukta-dev/issue-${start.issue}"}}}]
     + (if $pr == "1" then [pause("before-pr"; "before-pr"; 14400; "FAIL")] else [] end)
     + [step("pr"; "pr"; 1800; {YUKTA_CHECK_EXECUTION: "${payload.executionId}"}),
        {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}}]) as $nodes
    | {workflows: {
        ("implement-issue" + $suffix): {workflowId: ("implement-issue" + $suffix),
          description: "Implement one issue in its own worktree with a code-navigation index, check and heal it, open a draft PR",
          nodes: $nodes, edges: chain($nodes | map(.nodeId))},
        ("run-issue" + $suffix): runner("run-issue" + $suffix; "implement-issue" + $suffix; "implement-issue, and the issue back to Ready when it does not end well")}}'
}

# epic_variant IMPL_PAUSE PR_PAUSE BETWEEN_PAUSE: the suffix of run-epic for the checkpoints that are off (0)
epic_variant() { echo "$(issue_variant "$1" "$2")$([[ "$3" == 1 ]] || echo "-no-between-pause")"; }

# epic_workflows ISSUESH IMPL_PAUSE PR_PAUSE BETWEEN_PAUSE: prints {"workflows": {"run-epic...": ...}}. The start
# payload is epic (the issue number) and repo. run-epic lists the epic's open, assigned, Ready sub-issues,
# splits them and runs run-issue for each, two at a time (a worktree each, so their checks do not collide):
#
#   list -> split -> [between-issues] -> issue (SUB_WORKFLOW, concurrency 2) --success--> ok -> done
#                                              \--failure/escalate/retry--> bad -> escalate (OUTCOME ESCALATE)
#                                                                        \-> bad-count (2 of them) -> pause
#
# Two issue runs that end ESCALATE pause the epic and the runs below it (epic-pause), for whoever steers to
# look: yk resume --tree to go on, yk stop --tree to give up. Every issue still ends with its own PR or Ready again.
epic_workflows() {
  local suffix
  suffix="$(epic_variant "$2" "$3" "$4")"
  jq -n --arg sh "$1" --arg issue_suffix "$(issue_variant "$2" "$3")" --arg between "$4" --arg suffix "$suffix" '
    def env: {YUKTA_EPIC: "${start.epic}", YUKTA_REPO_DIR: "${start.repo}"}
             + (if $ENV.YUKTA_GH then {YUKTA_GH: $ENV.YUKTA_GH} else {} end);
    def edge($s; $t): {source: $s, target: $t};
    def lane($s; $port; $t): {source: $s, target: $t, sourcePort: $port};
    def agg($id): {nodeId: $id, type: "AGGREGATE", config: {size: 1000, timeoutSeconds: 86400}};
    ([{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}},
      {nodeId: "list", type: "PROCESS_EXECUTOR",
       config: {command: [$sh, "epic-list"], workingDir: "${start.repo}", timeout: 600, timeoutSeconds: 600,
                failureMode: "ERROR", outputFormat: "JSON", env: env}},
      {nodeId: "split", type: "SPLITTER", config: {itemsPath: "payload[\u0027output\u0027][\u0027issues\u0027]"}}]
     + (if $between == "1"
        then [{nodeId: "between-issues", type: "WAIT_FOR_SIGNAL",
               config: {signal: "between-issues", timeoutSeconds: 3600, onTimeout: "CONTINUE"}}] else [] end)
     + [{nodeId: "issue", type: "SUB_WORKFLOW",
         config: {workflowId: ("run-issue" + $issue_suffix), timeoutSeconds: 28800, concurrency: 2, routeByOutcome: true,
                  payload: {issue: "${payload.issue}", repo: "${start.repo}", worktree: "${payload.worktree}"}}},
        agg("ok"), agg("bad"),
        {nodeId: "bad-count", type: "AGGREGATE", config: {size: 2, timeoutSeconds: 86400}},
        {nodeId: "pause", type: "PROCESS_EXECUTOR",
         config: {command: [$sh, "epic-pause"], workingDir: "${start.repo}", timeout: 600, timeoutSeconds: 600,
                  failureMode: "CONTINUE", env: (env + {YUKTA_BAD_COUNT: "${payload.count}"})}},
        {nodeId: "paused", type: "CONSOLE_TERMINAL", config: {}},
        {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}},
        {nodeId: "escalate", type: "OUTCOME_TERMINAL",
         config: {outcome: "ESCALATE", reason: "issues of the epic did not finish: each is Ready again, its worktree kept"}}]) as $nodes
    | ($nodes | map(select(.nodeId == "between-issues")) | length > 0) as $gated
    | {workflows: {("run-epic" + $suffix): {workflowId: ("run-epic" + $suffix),
        description: "Run the Ready, assigned sub-issues of an epic, two at a time",
        nodes: $nodes,
        edges: ([edge("trigger"; "list"), edge("list"; "split")]
                + (if $gated then [edge("split"; "between-issues"), edge("between-issues"; "issue")] else [edge("split"; "issue")] end)
                + [lane("issue"; "success"; "ok"), edge("ok"; "done")]
                + [("failure", "escalate", "retry") as $p | lane("issue"; $p; "bad")]
                + [("failure", "escalate", "retry") as $p | lane("issue"; $p; "bad-count")]
                + [edge("bad"; "escalate"), edge("bad-count"; "pause"), edge("pause"; "paused")])}}}'
}

# dev_session HEALSH ISSUESH: the shared session "yukta-dev", parameterised by the start payload so it serves
# every checkout and every issue worktree: check (a project given as ${start.project}) with the heal and fix
# workflows it hands failures to, and the issue workflows.
dev_session() {
  local healsh="$1" issuesh="$2" policy base extras ci v ip pp bp
  policy="$(cat "$here/heal.json")"
  base="$(render_session "$here/session.json" "/unused" | jq '.sessionId = "yukta-dev" | .name = "yukta-dev"')"
  extras="$(heal_extras "$policy" "")"
  # check: the self-healing gate, a project given as ${start.project}; an issue run adds issueState (where its records are)
  ci="$(heal_workflow "$policy" "" '${start.project}' "$healsh" '${start.project}/.yukta-dev/heal/check' "$extras" check '${start.issueState}' <<<"$base")"
  # the workflows a failing stage of check hands over to
  ci="$(jq --argjson h "$(heal_subflows "$policy" "$healsh" "")" '.workflows += $h.workflows' <<<"$ci")"
  for ip in 1 0; do
    for pp in 1 0; do
      v="$(issue_workflows "$issuesh" "$ip" "$pp")"
      ci="$(jq --argjson v "$v" '.workflows += $v.workflows' <<<"$ci")"
      for bp in 1 0; do
        v="$(epic_workflows "$issuesh" "$ip" "$pp" "$bp")"
        ci="$(jq --argjson v "$v" '.workflows += $v.workflows' <<<"$ci")"
      done
    done
  done
  echo "$ci"
}
