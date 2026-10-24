#!/usr/bin/env bash
# Shared helpers for `yk` and the dogfood scripts. Sourced, not executed.

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
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
      YUKTA_SESSION_BASE_DIR="$DEV_HOME/sessions" YUKTA_SESSION_EXECUTION_TIMEOUT_SECONDS=14400 \
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

# heal_workflow POLICY MODULES PROJECT HEALSH HEALDIR LADDER: reads session JSON on stdin and adds the
# workflow "check", the gradle-check stages unrolled into ONE graph, so a run is one execution that
# `yk watch` follows and `yk retry` can steer. POLICY is heal.json (the ladder, which stages are
# analyzers, and per stage {auto, claude, ladder}); LADDER (comma separated) overrides its ladder.
#
# A sequential stage S (spotless, compile, tests, coverage) is a chain, rungs = [auto] + the ladder:
#   S.run1 --failure--> S.f1 --success--> S.run2 --failure--> S.f2 --success--> S.run3 ... S.run<k+1>
#          \--success--> next stage        \--failure--> S.f2 (the next rung)             (ERROR: the run fails)
# The attempt and model of every fixer are fixed in the graph: nothing counts at run time.
# Analyzers (style, spotbugs, ...) run in parallel after the last sequential stage; those with a policy
# go through a join, a gate and one shared fixer chain (analyzers.f<i> then ONE rerun analyzers.r<i>), the
# others (licenses, security) fail the run at once. verify re-runs everything if anything was healed.
heal_workflow() {
  # true when any analyzer of the join failed (an analyzer is skipped by the gate when none did)
  local gate="payload['items'].?[['success'] == false].size() > 0"
  jq --arg gate "$gate" --argjson policy "$1" --arg modules "$2" --arg project "$3" --arg healsh "$4" --arg healdir "$5" --arg lad "$6" '
    ($modules | split(" ") | map(select(. != ""))) as $mods
    | ($lad | split(",") | map(select(. != ""))) as $override
    | (if ($override | length) > 0 then $override else $policy.ladder end) as $ladder
    | def px: . as $t | if ($mods | length) > 0 and ($t | startswith(":") | not)
        then $mods[] | select(. != "cli") | ":" + . + ":" + $t else $t end;   # cli (Go) has no gradle fix tasks
    def gradle_tasks($n): [$n.config.command[1:][] | select(startswith("-") | not)] | join(" ");
    def rungs($n): ($policy.stages[$n.nodeId] // {}) as $p
        | (if $p.auto then ["auto"] else [] end) + (if $p.claude then ($p.ladder // $ladder) else [] end);
    # YUKTA_CLAUDE_BIN swaps the claude binary of the fixers (test-self-heal.sh uses a fake one)
    def env($tasks; $extra): {YUKTA_HEAL_DIR: $healdir, YUKTA_HEAL_TASKS: $tasks, YUKTA_MODULE: $modules}
        + (if $ENV.YUKTA_CLAUDE_BIN then {YUKTA_CLAUDE_BIN: $ENV.YUKTA_CLAUDE_BIN} else {} end) + $extra;
    def pe($id; $cfg): {nodeId: $id, type: "PROCESS_EXECUTOR",
        config: ({workingDir: $project, timeout: 3600, timeoutSeconds: 3600} + $cfg)};
    def routed($on): if $on then {failureMode: "CONTINUE", routeByExitCode: true} else {failureMode: "ERROR"} end;
    def runner($id; $n; $on): pe($id; {command: ([$healsh, "run", $n.nodeId, "--"] + $n.config.command), env: env(""; {})} + routed($on));
    def fixer($id; $stage; $i; $mode; $tasks; $extra):
        pe($id; {command: [$healsh, "fix", $stage, ($i | tostring), $mode], env: env($tasks; $extra)} + routed(true));
    def edge($s; $t; $port): {source: $s, target: $t} + (if $port == null then {} else {sourcePort: $port} end);
    def id($n; $pre; $i): $n + "." + $pre + ($i | tostring);

    # one sequential stage fed by $in = [{source, port}]; its exits are what the next stage hangs on
    def seq($n; $in):
        rungs($n) as $r | ($r | length) as $k | $n.nodeId as $s
        | {nodes: ([range(1; $k + 2) as $j | runner(id($s; "run"; $j); $n; $j <= $k)]
                   + [range(0; $k) as $i | fixer(id($s; "f"; $i + 1); $s; $i + 1; $r[$i];
                       (if $r[$i] == "auto" then (($policy.stages[$s].auto // []) | map(px) | join(" ")) else gradle_tasks($n) end); {})]),
           edges: ([$in[] | edge(.source; id($s; "run"; 1); .port)]
                   + [range(0; $k) as $i
                      | edge(id($s; "run"; $i + 1); id($s; "f"; $i + 1); "failure"),
                        edge(id($s; "f"; $i + 1); id($s; "run"; $i + 2); "success"),
                        edge(id($s; "f"; $i + 1); (if $i + 1 < $k then id($s; "f"; $i + 2) else id($s; "run"; $k + 1) end); "failure")]),
           exits: ([range(1; $k + 1) as $j | {source: id($s; "run"; $j), port: "success"}]
                   + [{source: id($s; "run"; $k + 1), port: null}])};

    .workflows["gradle-check"].nodes as $all
    | [$all[] | select(.type == "PROCESS_EXECUTOR")] as $stages
    | [$stages[] | select(.nodeId as $x | $policy.analyzers | index($x) | not)] as $sequential
    | [$stages[] | select(.nodeId as $x | $policy.analyzers | index($x))] as $analyzers
    | [$analyzers[] | select((rungs(.) | length) > 0)] as $healable
    | (reduce $sequential[] as $n ({nodes: [{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}}], edges: [],
                                    exits: [{source: "trigger", port: null}]};
          seq($n; .exits) as $s
          | {nodes: (.nodes + $s.nodes), edges: (.edges + $s.edges), exits: $s.exits})) as $head
    # analyzers: parallel, joined; the gate and the shared fixer chain only when one can be healed
    | ($ladder | length) as $k
    | (if ($analyzers | length) == 0 then $head
       else
         ([$healable[] | .nodeId + "=" + (.config.command | join(" "))] | join("\n")) as $list
         | {nodes: ($head.nodes
                    + [$analyzers[] | runner(.nodeId; .; ((rungs(.) | length) > 0))]
                    + [{nodeId: "analyzers.join", type: "AGGREGATE", config: {size: ($analyzers | length)}}]),
            edges: ($head.edges
                    + [$analyzers[] | .nodeId as $a | $head.exits[] | edge(.source; $a; .port)]
                    + [$analyzers[] | .nodeId as $a
                       | if (rungs(.) | length) > 0
                         then edge($a; "analyzers.join"; "success"), edge($a; "analyzers.join"; "failure")
                         else edge($a; "analyzers.join"; null) end]),
            exits: [{source: "analyzers.join", port: null}]}
         | if ($healable | length) == 0 then .
           else
             .nodes += [{nodeId: "analyzers.gate", type: "BRANCH",
                         config: {mode: "EXPRESSION", defaultPort: "pass",
                                  cases: {($gate): "fail"}}}]
             + [range(0; $k) as $i | fixer(id("analyzers"; "f"; $i + 1); "analyzers"; $i + 1; $ladder[$i]; "";
                  {YUKTA_HEAL_ANALYZERS: $list})]
             + [range(0; $k) as $i | pe(id("analyzers"; "r"; $i + 1);
                  {command: [$healsh, "rerun", "analyzers", ($i + 1 | tostring), "rerun"], env: env(""; {YUKTA_HEAL_ANALYZERS: $list})}
                  + routed($i + 1 < $k))]
             | .edges += [edge("analyzers.join"; "analyzers.gate"; null), edge("analyzers.gate"; id("analyzers"; "f"; 1); "fail")]
             + [range(0; $k) as $i
                | edge(id("analyzers"; "f"; $i + 1); id("analyzers"; "r"; $i + 1); "success"),
                  edge(id("analyzers"; "f"; $i + 1); (if $i + 1 < $k then id("analyzers"; "f"; $i + 2) else id("analyzers"; "r"; $k) end); "failure")]
             + [range(0; $k - 1) as $i | edge(id("analyzers"; "r"; $i + 1); id("analyzers"; "f"; $i + 2); "failure")]
             | .exits = [{source: "analyzers.gate", port: "pass"}]
                        + [range(0; $k - 1) as $i | {source: id("analyzers"; "r"; $i + 1), port: "success"}]
                        + [{source: id("analyzers"; "r"; $k), port: null}]
           end
       end) as $body
    | ([$stages[] | select(.config.command[0] == "./gradlew") | .config.command[1:][] | select(startswith("-") | not)]
       | unique | join(" ")) as $everything
    | del(.workflows["gradle-check"])
    | .workflows.check = {workflowId: "check", description: "The quality gate with self-healing, one execution",
        nodes: ($body.nodes
                + [pe("verify"; {command: [$healsh, "verify"], env: env($everything; {})} + routed(false)),
                   {nodeId: "done", type: "CONSOLE_TERMINAL", config: {}}]),
        edges: ($body.edges + [$body.exits[] | edge(.source; "verify"; .port)] + [edge("verify"; "done"; null)])}'
}

# check_stages: reads session JSON on stdin and prints the nodes of the check workflow, one per line.
check_nodes() { jq -r '.workflows.check.nodes[].nodeId'; }

# node_stage NODE: the stage a node of the check belongs to (S.run2 and S.f1 belong to S).
node_stage() { sed -E 's/\.(run|f|r)[0-9]+$//' <<<"$1"; }

# print_result SESSION EXECUTION: prints the machine readable RESULT line; returns 0 only when the
# execution ended SUCCESS.
print_result() {
  local progress status failed healed
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
  echo "RESULT status=$status failed_node=$failed healed=${healed:--} execution=$2 session=$1"
  [[ "$status" == COMPLETED || "$status" == SUCCESS ]]
}

# healed_stages SESSION [FAILED_STAGE]: "stage:step,..." for every heal step of the last check run
# that fixed its stage (records written by yukta-heal.sh, step = auto or the model), except the stage
# that still failed; empty when nothing was healed.
healed_stages() {
  local dir="$STATE_DIR/heal/$1"
  [[ -d "$dir" ]] || return 0
  jq -rs --arg failed "${2:--}" '[.[] | select(.fixed and .stage != $failed) | "\(.stage):\(.mode)"] | unique | join(",")' \
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
    def stage: sub("\\.(run|f|r)[0-9]+$"; "");
    (.data // .) as $p
    | [$p.tasks[] | select((.startTime // "0001")[:4] != "0001" and (.endTime // "0001")[:4] != "0001")
       | {stage: (.nodeId | stage), secs: ((.endTime | secs) - (.startTime | secs))}]
    | group_by(.stage)[] | "\($p.workflowId)\t\(.[0].stage)\t\(map(.secs) | add)"')
}
