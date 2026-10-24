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
      YUKTA_SUPERVISOR_RULES_FILE="$rules" YUKTA_API_KEY="$(dev_key)"
    setsid bash -c 'echo $$ >"$1"; shift; exec "$@"' _ "$DEV_HOME/server.pid" "$@" \
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
YUKTA_DEV_KEYED=1
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
    if [[ "$running" == "$id" && "$(server_env YUKTA_DEV_KEYED)" == 1 ]]; then
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
  if [[ ("$sha" != "$(server_env YUKTA_DEV_SHA)" || "$(server_env YUKTA_DEV_KEYED)" != 1) \
    && -s "$DEV_HOME/builds/$sha/yukta.jar" && "$(busy)" == 0 ]]; then
    (server_up) >&2 || true
  fi
}

# ---- The staged, self-healing check ----

# stage_workflows POLICY MODULES PROJECT HEALSH HEALDIR: reads session JSON on stdin and adds one small
# workflow "stage-<stage>" per stage of gradle-check (after scoping), each with several exits that an
# OUTCOME_TERMINAL names. The supervisor (rules.json) restarts a RETRY; the driver (run_stages) acts
# on ESCALATE and moves on after SUCCESS. POLICY is heal.json, the stage -> {auto, claude} map;
# MODULES is the space-separated module scope (empty: the whole project).
#   run ──success──────────────▶ OUT SUCCESS
#     └failure▶ apply ─success▶ OUT RETRY (restartFrom run)     (stage has a deterministic fix)
#                  └failure───▶ OUT ESCALATE
#     └failure▶ OUT ESCALATE                                    (claude may fix it, no auto fix)
#     └failure▶ OUT FAILED                                      (no policy: fail fast)
stage_workflows() {
  jq --argjson policy "$1" --arg modules "$2" --arg project "$3" --arg healsh "$4" --arg healdir "$5" '
    ($modules | split(" ") | map(select(. != ""))) as $mods
    | def px: . as $t | if ($mods | length) > 0 and ($t | startswith(":") | not)
        then $mods[] | select(. != "cli") | ":" + . + ":" + $t else $t end;   # cli (Go) has no gradle fix tasks
    def out($id; $o): {nodeId: $id, type: "OUTCOME_TERMINAL", config: ({outcome: $o} + (if $o == "RETRY" then {restartFrom: "run"} else {} end))};
    def edge($s; $t; $p): {source: $s, target: $t, sourcePort: $p};
    def stage($n):
      ($policy[$n.nodeId] // {}) as $p
      | ($n | .nodeId = "run" | .config.failureMode = "CONTINUE" | .config.routeByExitCode = true) as $run
      | ({nodeId: "apply", type: "PROCESS_EXECUTOR",
          config: {command: [$healsh, $n.nodeId, "0", "auto"], workingDir: $project,
            timeout: 3600, timeoutSeconds: 3600, failureMode: "CONTINUE", routeByExitCode: true,
            env: {YUKTA_HEAL_DIR: $healdir, YUKTA_HEAL_TASKS: (($p.auto // []) | map(px) | join(" ")), YUKTA_MODULE: $modules}}}) as $apply
      | {workflowId: ("stage-" + $n.nodeId), description: ("Stage " + $n.nodeId + " with explicit exits"),
         nodes: ([{nodeId: "trigger", type: "CONSTANT_SOURCE", config: {}}, $run, out("ok"; "SUCCESS")]
                 + (if $p.auto then [$apply, out("retry"; "RETRY"), out("escalate"; "ESCALATE")]
                    elif $p.claude then [out("escalate"; "ESCALATE")]
                    else [out("failed"; "FAILED")] end)),
         edges: ([{source: "trigger", target: "run"}, edge("run"; "ok"; "success")]
                 + (if $p.auto then [edge("run"; "apply"; "failure"), edge("apply"; "retry"; "success"), edge("apply"; "escalate"; "failure")]
                    elif $p.claude then [edge("run"; "escalate"; "failure")]
                    else [edge("run"; "failed"; "failure")] end))};
    if .workflows["gradle-check"] == null then . else
      .workflows += ([.workflows["gradle-check"].nodes[] | select(.type == "PROCESS_EXECUTOR") | stage(.)]
                     | map({(.workflowId): .}) | add // {})
    end'
}

# stage_names: reads session JSON on stdin, prints the stages of gradle-check in order.
stage_names() {
  jq -r '.workflows["gradle-check"].nodes[] | select(.type == "PROCESS_EXECUTOR") | .nodeId'
}

# stage_tasks STAGE: reads session JSON on stdin, prints the gradle tasks of the stage (as scoped).
stage_tasks() {
  jq -r --arg s "$1" '.workflows["gradle-check"].nodes[] | select(.nodeId == $s)
    | [.config.command[1:][] | select(startswith("-") | not)] | join(" ")'
}

_history() { "$(find_cli)" -o json workflow history "$1" | jq 'if type == "object" then (.data // .) else . end'; }

# follow_stage SESSION WORKFLOW EXECUTION: waits until the stage workflow's newest execution has ended
# and prints its id. A RETRY outcome is restarted by the server (rules.json) into a new execution;
# after an execution ends this waits a few seconds for such a successor before settling on it.
# ponytail: assumes startTime sorts as text (ISO-8601) and a 4s grace; without rules.json the
# RETRY simply settles here and the driver treats it as an escalation.
follow_stage() {
  local session="$1" wf="$2" first="$3" settled="" row id status
  for _ in $(seq 1 3600); do
    row="$(_history "$session" | jq -r --arg wf "$wf" --arg first "$first" '
      ([.[] | select(.executionId == $first) | .startTime][0]) as $t0
      | [.[] | select(.workflowId == $wf and (.startTime >= $t0))] | sort_by(.startTime) | last
      | "\(.executionId) \(.status)"')"
    id="${row%% *}" status="${row#* }"
    if [[ "$status" =~ ^(PENDING|QUEUED|RUNNING|PAUSED)$ || -z "$id" || "$id" == null ]]; then
      settled=""
      sleep 2
    elif [[ "$settled" == "$id" ]]; then
      echo "$id"
      return 0
    else
      settled="$id"
      sleep 4
    fi
  done
  die "stage $wf did not end"
}

# stage_outcome SESSION EXECUTION: the outcome of the OUTCOME_TERMINAL the run reached (SUCCESS, RETRY,
# ESCALATE, FAILED), or NONE.
stage_outcome() {
  "$(find_cli)" -o json workflow status "$1" "$2" \
    | jq -r '(.data // .) | [.tasks[] | select(.module == "OUTCOME_TERMINAL" and .metadata.reached == true) | .metadata.outcome][0] // "NONE"'
}

start_stage() { # session workflow
  "$(find_cli)" -o json workflow start "$1" "$2" | jq -r '.data.executionId // .executionId'
}

# run_stages SESSION SESSIONFILE: the supervisor-side loop of a healing check. For each stage, start
# its workflow and follow it; on SUCCESS go on, on ESCALATE (or a RETRY the server did not restart)
# let the Claude fixer try (two attempts, modes $m1 and $m2), then start the stage again; FAILED or an
# unfixed stage ends the check. Finally re-run every task once if anything was healed. Prints STAGE
# lines and the RESULT line; returns 0 only when everything passed.
# Variables: project modules healsh heal_dir fork m1 m2.
run_stages() {
  local session="$1" file="$2" stage exec outcome attempt mode fixed last="-" failed="-" tasks all
  all="$(jq -r '[.workflows["gradle-check"].nodes[] | select(.type == "PROCESS_EXECUTOR") | .config.command[1:][] | select(startswith("-") | not)] | unique | join(" ")' "$file")"
  while read -r stage; do
    echo "STAGE $stage start"
    exec="$(start_stage "$session" "stage-$stage")"
    attempt=0
    while :; do
      exec="$(follow_stage "$session" "stage-$stage" "$exec")"
      last="$exec"
      outcome="$(stage_outcome "$session" "$exec")"
      echo "STAGE $stage $outcome execution=$exec"
      [[ "$outcome" == SUCCESS ]] && break
      if [[ "$outcome" == FAILED || "$outcome" == NONE || $attempt -ge 2 ]]; then failed="$stage"; break 2; fi
      attempt=$((attempt + 1))
      mode="$m1"; ((attempt == 2)) && mode="$m2"
      tasks="$(stage_tasks "$stage" <"$file")"
      "$(find_cli)" -o json workflow status "$session" "$exec" \
        | jq -r '(.data // .) | .tasks[] | select(.metadata.error != null) | "ERROR \(.nodeId): \(.metadata.error | .[0:300])"' | head -3
      (cd "$project" && YUKTA_HEAL_DIR="$heal_dir" YUKTA_HEAL_TASKS="$tasks" YUKTA_FORK_SESSION="$fork" \
        YUKTA_MODULE="$modules" "$healsh" "$stage" "$attempt" "$mode") | tail -1
      fixed="$(jq -r '.fixed' "$heal_dir/$stage-$attempt.json" 2>/dev/null || echo false)"
      [[ "$fixed" == true ]] || continue
      exec="$(start_stage "$session" "stage-$stage")"
    done
  done < <(stage_names <"$file")
  if [[ "$failed" == "-" ]] && jq -e -s 'any(.[]; .fixed)' "$heal_dir"/*.json >/dev/null 2>&1; then
    (cd "$project" && YUKTA_HEAL_DIR="$heal_dir" YUKTA_HEAL_TASKS="$all" YUKTA_MODULE="$modules" \
      "$healsh" verify 0 verify) || failed="verify"
  fi
  local healed status=COMPLETED
  healed="$(healed_stages "$session" "$failed")"
  [[ "$failed" == "-" ]] || status=FAILURE
  echo "RESULT status=$status failed_node=$failed healed=${healed:--} execution=$last session=$session"
  [[ "$status" == COMPLETED ]]
}

# print_result SESSION EXECUTION: prints the machine readable RESULT line; returns 0 only when the
# execution ended COMPLETED/SUCCESS.
print_result() {
  local progress status failed healed
  progress="$("$(find_cli)" -o json workflow status "$1" "$2" | jq '(.data // .)')"
  status="$(jq -r '.status' <<<"$progress")"
  failed="$(jq -r '[.tasks[] | select(.status | test("FAIL|ERROR"; "i")) | .nodeId][0] // "-" | .' <<<"$progress")"
  healed="$(healed_stages "$1" "$failed")"
  echo "RESULT status=$status failed_node=$failed healed=${healed:--} execution=$2 session=$1"
  [[ "$status" == COMPLETED || "$status" == SUCCESS ]]
}

# healed_stages SESSION [FAILED_STAGE]: "stage:step,..." for every heal step of the last check run
# that fixed its stage (records written by yukta-heal.sh), except the stage that still failed; empty
# when nothing was healed.
healed_stages() {
  local dir="$STATE_DIR/heal/$1"
  [[ -d "$dir" ]] || return 0
  jq -rs --arg failed "${2:--}" '[.[] | select(.fixed and .stage != $failed) | "\(.stage):\(if .attempt == 0 then "auto" else "fix\(.attempt)" end)"] | unique | join(",")' \
    "$dir"/*.json 2>/dev/null || true
}

# record_durations SESSION EXECUTION SCOPE: appends each finished node's duration to
# $STATE_DIR/durations.tsv (date, workflow:scope, node, seconds, execution) and prints
#   SLOW <node> <s>s (median <m>s, <n> runs)
# for a node that took over 15s and 1.5x the median of its last 10 runs at the same workflow and
# scope (at least 3 earlier runs). SCOPE is the module, "all" or the gradle args of the run.
# The server stamps every node's start with the run's start (nodes are wired up front), so a
# node's duration is the gap between its end and the previous node's end.
# ponytail: exact for the linear dogfood workflows only (parallel branches would be mis-timed),
# whole-second resolution, and a plain median (failed fast runs count); use a percentile if noisy.
record_durations() {
  local session="$1" exec="$2" scope="$3" file="$STATE_DIR/durations.tsv"
  local wf node secs key prior n median
  mkdir -p "$STATE_DIR"
  touch "$file"
  while IFS=$'\t' read -r wf node secs; do
    key="$wf:$scope"
    prior="$(awk -F'\t' -v k="$key" -v n="$node" '$2 == k && $3 == n { print $4 }' "$file" | tail -10 | sort -n)"
    n="$(grep -c . <<<"$prior" || true)"
    if ((n >= 3 && secs > 15)); then
      median="$(awk '{ a[NR] = $1 } END { print a[int((NR + 1) / 2)] }' <<<"$prior")"
      if ((secs * 2 > median * 3)); then
        echo "SLOW $node ${secs}s (median ${median}s, $n runs)"
      fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$(date -Is)" "$key" "$node" "$secs" "$exec" >>"$file"
  done < <("$(find_cli)" -o json workflow status "$session" "$exec" | jq -r '
    def secs: .[:19] | strptime("%Y-%m-%dT%H:%M:%S") | mktime;
    (.data // .) as $p
    | [$p.tasks[] | select((.endTime // "0001")[:4] != "0001") | {node: .nodeId, end: (.endTime | secs)}]
    | sort_by(.end) as $t
    | range(0; $t | length) as $i
    | "\($p.workflowId)\t\($t[$i].node)\t\($t[$i].end - (if $i == 0 then ($p.startTime | secs) else $t[$i - 1].end end))"')
}
