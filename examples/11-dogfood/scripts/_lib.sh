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
# (yukta-<os>-<arch> from `gradlew :cli:goBuild`, or yukta from `make build`).
find_cli() {
  if [[ -n "${YUKTA_BIN:-}" ]]; then echo "$YUKTA_BIN"; return; fi
  if command -v yukta >/dev/null 2>&1; then command -v yukta; return; fi
  local os arch f
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  case "$(uname -m)" in
    x86_64 | amd64) arch=amd64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) arch="$(uname -m)" ;;
  esac
  f="$(ls -t "$REPO_ROOT/cli/build/yukta-$os-$arch" "$REPO_ROOT/cli/build/yukta" 2>/dev/null | head -1)"
  [[ -n "$f" && -x "$f" ]] && { echo "$f"; return; }
  die "yukta CLI for $os/$arch not found; run ./gradlew :cli:goBuild or set YUKTA_BIN"
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
    .workflows["gradle-check"] |= (
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
}

# print_result SESSION EXECUTION: prints the machine readable RESULT line; returns 0 only when the
# execution ended COMPLETED/SUCCESS.
print_result() {
  local progress status failed
  progress="$("$(find_cli)" -o json workflow status "$1" "$2" | jq '(.data // .)')"
  status="$(jq -r '.status' <<<"$progress")"
  failed="$(jq -r '[.tasks[] | select(.status | test("FAIL|ERROR"; "i")) | .nodeId][0] // "-"' <<<"$progress")"
  echo "RESULT status=$status failed_node=$failed execution=$2 session=$1"
  [[ "$status" == COMPLETED || "$status" == SUCCESS ]]
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
