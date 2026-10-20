#!/usr/bin/env bash
# Shared helpers for the dogfood scripts. Sourced, not executed.

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
STATE_DIR="${YUKTA_DEV_STATE_DIR:-$REPO_ROOT/.yukta-dev}"

die() { echo "error: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not on PATH"; }

# valid_name NAME: names end up in file paths, a branch and a worktree path, so keep them plain.
valid_name() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "invalid name '$1' (use letters, digits, . _ -)"
}

# Locate the yukta CLI: $YUKTA_BIN, then PATH, then the newest build for this OS/arch in cli/build
# (yukta-cli-<os>-<arch> from `gradlew :cli:goBuild`, or yukta-cli from `make build`).
find_cli() {
  if [[ -n "${YUKTA_BIN:-}" ]]; then echo "$YUKTA_BIN"; return; fi
  if command -v yukta-cli >/dev/null 2>&1; then command -v yukta-cli; return; fi
  local os arch f
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  case "$(uname -m)" in
    x86_64 | amd64) arch=amd64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) arch="$(uname -m)" ;;
  esac
  f="$(ls -t "$REPO_ROOT/cli/build/yukta-cli-$os-$arch" "$REPO_ROOT/cli/build/yukta-cli" 2>/dev/null | head -1)"
  [[ -n "$f" && -x "$f" ]] && { echo "$f"; return; }
  die "yukta-cli for $os/$arch not found; run ./gradlew :cli:goBuild or set YUKTA_BIN"
}

# render_session TEMPLATE PROJECT_PATH: prints the session JSON with __PROJECT_PATH__ replaced.
# Done on parsed JSON so any characters in the path are escaped correctly.
render_session() {
  jq --arg p "$2" 'walk(if type == "string" then split("__PROJECT_PATH__") | join($p) else . end)' "$1"
}

# scope_to_module MODULE: reads session JSON on stdin, rewrites the gradle-check workflow to run
# only MODULE's tasks (MODULE is a gradle path without the leading colon, e.g. core or
# plugins:processors:decision). Plain tasks get a ":MODULE:" prefix; tasks pinned to another module
# and the root-only trivy are dropped; stages left empty disappear and, the workflow being a linear
# chain, the edges are rebuilt. cli is a Go module: only its explicit :cli: tasks apply.
scope_to_module() {
  jq --arg m "$1" '
    def scoped($cmd):
      [$cmd[1:][] | select(startswith("-") | not)
        | if startswith(":") then select(startswith(":" + $m + ":"))
          elif . == "trivy" or $m == "cli" then empty
          else ":" + $m + ":" + . end] as $t
      | if ($t | length) == 0 then null
        else [$cmd[0]] + $t + [$cmd[1:][] | select(startswith("-"))] end;
    (.workflows["gradle-check"], (.workflows["format"] | select(. != null))) |= (
      .nodes |= [.[] | if .type == "PROCESS_EXECUTOR"
          then (scoped(.config.command) as $c | if $c == null then empty else .config.command = $c end)
          else . end]
      | .edges = [range(1; .nodes | length) as $i
          | {source: .nodes[$i - 1].nodeId, target: .nodes[$i].nodeId}])'
}

# splice_args ARGS...: reads session JSON on stdin, replaces the "__GRADLE_ARGS__" element of the
# gradle-run command with ARGS, each kept as its own argv entry so spaces survive.
splice_args() {
  jq '.workflows["gradle-run"].nodes |= map(if .config.command
        then .config.command |= [.[] | if . == "__GRADLE_ARGS__" then $ARGS.positional[] else . end]
        else . end)' --args -- "$@"
}

# ---- Server providers (yukta-worktree.sh, yukta-native.sh) share these ----

# free_port: the first free local port from 9101 upward.
free_port() {
  local port=9101
  while (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; do port=$((port + 1)); done
  echo "$port"
}

# launch_server NAME WORKDIR CMD...: runs CMD in WORKDIR as its own process group (so stop_server
# can stop it with its children), log in $STATE_DIR/NAME.log, pid in $STATE_DIR/NAME.pid.
# The caller exports the server's environment (SERVER_PORT, YUKTA_SESSION_BASE_DIR, ...).
launch_server() {
  local name="$1" dir="$2"
  shift 2
  mkdir -p "$STATE_DIR"
  rm -f "$STATE_DIR/$name.pid"
  (
    cd "$dir"
    setsid bash -c 'echo $$ >"$1"; shift; exec "$@"' _ "$STATE_DIR/$name.pid" "$@" \
      >"$STATE_DIR/$name.log" 2>&1 </dev/null &
  )
  for _ in 1 2 3 4 5; do [[ -s "$STATE_DIR/$name.pid" ]] && break; sleep 1; done
}

# wait_healthy NAME URL SECONDS: waits for URL/actuator/health, failing if the server exits first.
wait_healthy() {
  local name="$1" url="$2" timeout_s="$3"
  echo "waiting for Yukta at $url (log: $STATE_DIR/$name.log) ..."
  for _ in $(seq 1 $((timeout_s / 2))); do
    kill -0 "$(cat "$STATE_DIR/$name.pid")" 2>/dev/null || die "server exited early; see $STATE_DIR/$name.log"
    if curl -fsS "$url/actuator/health" >/dev/null 2>&1; then
      echo "ready: export YUKTA_SERVER_URL=$url"
      return 0
    fi
    sleep 2
  done
  die "server did not become healthy in ${timeout_s}s; see $STATE_DIR/$name.log"
}

# stop_server NAME: stops the server of launch_server (whole process group) and forgets its pid.
stop_server() {
  local pidfile="$STATE_DIR/$1.pid" pid
  [[ -f "$pidfile" ]] || return 0
  pid="$(cat "$pidfile")"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 -- "-$pid" 2>/dev/null; then
    kill -TERM -- "-$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 -- "-$pid" 2>/dev/null || break
      sleep 1
    done
    kill -KILL -- "-$pid" 2>/dev/null || true
  elif [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    # Started by an older version of the script (no own group): stop the process and its children.
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
    sleep 5
  fi
  rm -f "$pidfile"
}

# forget_server NAME: removes NAME's env file and, if it is the current server, current.env too.
forget_server() {
  rm -f "$STATE_DIR/$1.env"
  if [[ "$(grep -s YUKTA_DEV_NAME "$STATE_DIR/current.env")" == "YUKTA_DEV_NAME=$1" ]]; then
    rm -f "$STATE_DIR/current.env"
  fi
}

# load_server: pick the server for the yukta CLI: $YUKTA_SERVER_URL, else the one started by
# yukta-worktree.sh (.yukta-dev/current.env), else localhost:8080.
load_server() {
  if [[ -z "${YUKTA_SERVER_URL:-}" && -f "$STATE_DIR/current.env" ]]; then
    # shellcheck disable=SC1091
    source "$STATE_DIR/current.env"
  fi
  export YUKTA_SERVER_URL="${YUKTA_SERVER_URL:-http://localhost:8080}"
  warn_if_stale_native
}

# warn_if_stale_native: the native server (yukta-native.sh) keeps running the executable it started
# with, recorded as YUKTA_SERVER_BIN in its state file; say so when that executable has been rebuilt.
warn_if_stale_native() {
  local pidfile="$STATE_DIR/${YUKTA_DEV_NAME:-}.pid" envfile="$STATE_DIR/${YUKTA_DEV_NAME:-}.env" bin
  [[ "${YUKTA_DEV_MODE:-}" == native && -s "$pidfile" ]] || return 0
  bin="$(sed -n 's/^YUKTA_SERVER_BIN=//p' "$envfile" 2>/dev/null)" || return 0
  [[ -n "$bin" && -f "$bin" ]] || return 0
  local started
  started="$(ps -o lstart= -p "$(cat "$pidfile")" 2>/dev/null | xargs -I{} date -d {} +%s)" || return 0
  [[ -n "$started" && "$(stat -c %Y "$bin")" -gt "$started" ]] \
    && echo "STALE: $YUKTA_DEV_NAME predates $bin; restart it: yukta-server.sh stop $YUKTA_DEV_NAME && yukta-server.sh start $YUKTA_DEV_NAME" >&2
  return 0
}

# self_heal: reads session JSON on stdin and rewrites the gradle-check workflow so a failing stage
# repairs itself (the policy is heal.json, the stage -> {auto, claude} map). Run it LAST, after
# scope_to_module/splice_args, which rebuild the edges as one linear chain.
#   stage S ──success──────────────────────────────────────────────▶ next stage
#     └failure▶ S-auto ▶ S-r1 ─success──────────────────────────────▶ next stage   (deterministic fix)
#                 └failure▶ S-fix1 ▶ S-r2 ─success──────────────────▶ next stage   (claude, attempt 1)
#                              └failure▶ S-fix2 ▶ S-r3 (fails the run) ─▶ next stage  (claude, attempt 2)
# then a "verify" node before the terminal re-runs every stage's tasks when anything was healed. The
# DAG cannot loop, so the repeats are unrolled; the engine does not mark untaken nodes as skipped,
# so what was healed is read from the records yukta-heal.sh writes, not from node statuses.
# Variables: policy (JSON), module, project, healsh, healdir, fork (claude session to fork, may be
# empty), m1 and m2 (fixer mode of attempt 1 and 2: fresh or fork).
self_heal() {
  jq --argjson policy "$1" --arg module "$2" --arg project "$3" --arg healsh "$4" --arg healdir "$5" \
    --arg fork "$6" --arg m1 "$7" --arg m2 "$8" '
    def tasks: [.[1:][] | select(startswith("-") | not)];
    def px: if $module != "" and (startswith(":") | not) then ":" + $module + ":" + . else . end;
    def uniq: reduce .[] as $t ([]; if index($t) then . else . + [$t] end);
    def edge($s; $t; $p): {source: $s, target: $t} + (if $p then {sourcePort: $p} else {} end);
    def healnode($id; $stage; $attempt; $mode; $tasks): {
      nodeId: $id, type: "PROCESS_EXECUTOR",
      config: {command: [$healsh, $stage, ($attempt | tostring), $mode], workingDir: $project,
        timeout: 3600, timeoutSeconds: 3600, failureMode: "CONTINUE",
        env: {YUKTA_HEAL_DIR: $healdir, YUKTA_HEAL_TASKS: ($tasks | join(" ")),
          YUKTA_FORK_SESSION: $fork, YUKTA_MODULE: $module}}};
    def expand:
      . as $n
      | ($policy[$n.nodeId] // {}) as $p
      | ($n.config.command // [] | tasks) as $t
      | [(if $p.auto then {id: ($n.nodeId + "-auto"), attempt: 0, mode: "auto", tasks: ($p.auto | map(px))} else empty end),
         (if $p.claude then
            ({id: ($n.nodeId + "-fix1"), attempt: 1, mode: $m1, tasks: $t},
             {id: ($n.nodeId + "-fix2"), attempt: 2, mode: $m2, tasks: $t})
          else empty end)] as $steps
      | ($steps | length) as $k
      | if $n.type != "PROCESS_EXECUTOR" or $k == 0
        then {entry: $n.nodeId, nodes: [$n], edges: [], outs: [{from: $n.nodeId}]}
        else
          ($n | .config.failureMode = "CONTINUE" | .config.routeByExitCode = true) as $head
          | [range(0; $k) as $i | $n | .nodeId = ($n.nodeId + "-r" + ($i + 1 | tostring))
              | if $i < $k - 1 then .config.failureMode = "CONTINUE" | .config.routeByExitCode = true else . end] as $checks
          | {entry: $n.nodeId,
             nodes: ([$head] + [range(0; $k) as $i
                       | healnode($steps[$i].id; $n.nodeId; $steps[$i].attempt; $steps[$i].mode; $steps[$i].tasks), $checks[$i]]),
             edges: ([edge($n.nodeId; $steps[0].id; "failure")]
                     + [range(0; $k) as $i | edge($steps[$i].id; $checks[$i].nodeId; null)]
                     + [range(0; $k - 1) as $i | edge($checks[$i].nodeId; $steps[$i + 1].id; "failure")]),
             outs: ([{from: $n.nodeId, port: "success"}]
                    + [range(0; $k - 1) as $i | {from: $checks[$i].nodeId, port: "success"}]
                    + [{from: $checks[$k - 1].nodeId}])}
        end;
    if .workflows["gradle-check"] == null then . else
    .workflows["gradle-check"] |= (
      . as $wf
      | ([$wf.nodes[] | select(.type == "PROCESS_EXECUTOR") | .config.command | tasks] | add // [] | uniq) as $all
      | ($wf.nodes | map(expand)) as $recs
      | {entry: "verify",
         nodes: [{nodeId: "verify", type: "PROCESS_EXECUTOR",
                  config: {command: [$healsh, "verify", "0", "verify"], workingDir: $project,
                    timeout: 3600, timeoutSeconds: 3600, failureMode: "ERROR",
                    env: {YUKTA_HEAL_DIR: $healdir, YUKTA_HEAL_TASKS: ($all | join(" ")), YUKTA_MODULE: $module}}}],
         edges: [], outs: [{from: "verify"}]} as $verify
      | ($recs[:-1] + [$verify] + $recs[-1:]) as $seq
      | .nodes = [$seq[].nodes[]]
      | .edges = ([$seq[].edges[]]
          + [range(0; $seq | length - 1) as $i | $seq[$i].outs[] | edge(.from; $seq[$i + 1].entry; .port)]))
    end'
}

# print_result SESSION EXECUTION: prints the machine readable RESULT line; returns 0 only when the
# execution ended COMPLETED/SUCCESS.
print_result() {
  local progress status failed healed
  progress="$("$(find_cli)" -o json workflow status "$1" "$2" | jq '(.data // .)')"
  status="$(jq -r '.status' <<<"$progress")"
  failed="$(jq -r '[.tasks[] | select(.status | test("FAIL|ERROR"; "i")) | .nodeId][0] // "-" | sub("-(auto|fix[0-9]+|r[0-9]+)$"; "")' <<<"$progress")"
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
