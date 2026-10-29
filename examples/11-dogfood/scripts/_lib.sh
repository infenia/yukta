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

# render_session TEMPLATE PROJECT_PATH: prints the session JSON with __PROJECT_PATH__ (the checkout) and __DOGFOOD__ (this folder) replaced.
# Done on parsed JSON so any characters in the path are escaped correctly.
render_session() {
  jq --arg p "$2" --arg d "$here" 'walk(if type == "string" then split("__PROJECT_PATH__") | join($p) | split("__DOGFOOD__") | join($d) else . end)' "$1"
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
      YUKTA_CONCURRENCY_GROUPS_GRADLE="${YUKTA_DEV_GRADLE_PARALLEL:-2}" YUKTA_CONCURRENCY_GROUPS_TOOL="${YUKTA_DEV_GRADLE_PARALLEL:-2}" \
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

# heal_events_file: where heal's monitoring events go: YUKTA_HEAL_EVENTS, else heal-events.jsonl in the dev state dir; "off" or an empty
# YUKTA_HEAL_EVENTS turns it off (prints nothing). One append-only file per machine: it outlives the per-run heal dirs.
heal_events_file() {
  local f="${YUKTA_HEAL_EVENTS-$DEV_HOME/heal-events.jsonl}"
  [[ "$f" == off ]] && f=""
  printf '%s' "$f"
}

# node_stage NODE: the stage a node of the check belongs to (S.heal and S.failed belong to S).
node_stage() { sed -E 's/\.(run[0-9]+|f[0-9]+|r[0-9]+|pause|heal)$//' <<<"$1"; }

# emit_result SESSION EXECUTION STATUS FAILED HEALED PROGRESS: the `result` event of the monitoring log: how the run ended, its wall time,
# and the seconds each node took (a node reports its own start, so a stage is the sum of its nodes: S.run1, S.f1, S.heal ...).
# Every run ends in exactly one result with a terminal status: a run that is still going has no result yet, and a run that already has
# one (yk result after yk watch) does not get a second.
emit_result() {
  local events project
  events="$(heal_events_file)"
  [[ -n "$events" ]] || return 0
  case "$3" in RUNNING | PENDING | QUEUED | PAUSED) return 0 ;; esac
  if [[ -s "$events" ]] && grep -F "\"run\":\"$2\"" "$events" 2>/dev/null | grep -qF '"event":"result"'; then return 0; fi
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

# check_session PROJECT NARGS: the session a check of PROJECT runs in on the shared server. The path hash keeps
# checkouts apart (two clones) and keeps one session per checkout across branch switches. A run with gradle args (yk gradle) gets a session of its own:
# applying a session replaces its workflows and a run clears its heal records, so a yk gradle of a fixer in the
# checkout (the pre-bash hook sends it there) must not share the session of the check it is healing.
check_session() {
  local suffix=""
  (( ${2:-0} > 0 )) && suffix="-gradle"
  echo "check-$(printf '%s' "$1" | sha1sum | cut -c1-8)$suffix"
}

# print_result SESSION EXECUTION: prints the machine readable RESULT line; returns 0 only when the
# execution ended SUCCESS.
print_result() {
  local progress status failed healed outcome records
  progress="$("$(find_cli)" -o json workflow status "$1" "$2" | jq '(.data // .)')"
  records="$(heal_records "$1")"
  status="$(jq -r '.status' <<<"$progress")"
  failed="$(jq -r '[.tasks[] | select(.status == "FAILURE" or .status == "TIMED_OUT") | .nodeId][0] // "-"' <<<"$progress")"
  if [[ "$failed" != - ]]; then
    failed="$(node_stage "$failed")"
    # the analyzers share one fixer chain; name the ones still failing
    if [[ "$failed" == analyzers && -s "$records/analyzers.failed" ]]; then
      failed="$(cat "$records/analyzers.failed")"
    fi
  fi
  healed="$(healed_stages "$1" "$failed" "$records")"
  # a run can end well and still not be done: an OUTCOME_TERMINAL that reached ESCALATE, FAILED or RETRY
  outcome="$(jq -r '[.tasks[] | select(.module == "OUTCOME_TERMINAL" and .metadata.reached == true) | .metadata.outcome][0] // "-"' <<<"$progress")"
  [[ "$status" == SUCCESS && "$outcome" != - && "$outcome" != SUCCESS ]] && status="$outcome"
  # the tool and check workflows fail by outcome, not by a failed node: the stage is the reached terminal's name
  # (<stage>.failed in check, failed in tool)
  if [[ "$failed" == - && "$outcome" == FAILED ]]; then
    failed="$(jq -r '[.tasks[] | select(.module == "OUTCOME_TERMINAL" and .metadata.reached == true) | .nodeId][0] // "tool"' <<<"$progress")"
    failed="${failed%.failed}"
    [[ "$failed" == failed ]] && failed=tool
  fi
  # yk check: the failed stage's own output (the last 40 lines), which lives in the terminal's payload
  local shown="" wall cost line
  if [[ -n "${YUKTA_FAILED_OUTPUT:-}" && "$outcome" == FAILED ]]; then
    shown="$("$(find_cli)" -o json result "$1" "$2" 2>/dev/null \
      | jq -rs '[.[] | select(.kind == "RESULT" and .payload.outcome == "FAILED")][0].payload.results[0].stdout // empty' \
      | tail -n 40 | sed "s/^/OUTPUT $failed: /")"
    [[ -z "$shown" ]] || printf '%s\n' "$shown"
  fi
  emit_result "$1" "$2" "$status" "$failed" "${healed:--}" "$progress"
  # what the run cost: wall seconds (first node start to last node end), heal dollars (the records of every fix attempt), and the bytes the
  # orchestrator was shown (the watched lines, the failed output, this line)
  wall="$(run_wall "$progress")"
  cost="$(cat "$records"/*-[0-9]*.json 2>/dev/null | jq -s 'map(.cost // 0) | add // 0 | . * 100 | round / 100' 2>/dev/null || true)"
  line="RESULT status=$status failed_node=$failed healed=${healed:--} execution=$2 session=$1 wall=${wall:-0} cost=\$${cost:-0}"
  # the next level: the full text of the failure is one command away
  local more=""
  [[ "$status" == SUCCESS || "$status" == COMPLETED ]] || more="MORE yk detail $1 $2 [--grep PATTERN] [--tail N] [--head N]"
  local bytes=$(( ${YUKTA_WATCHED_BYTES:-0} + ${#shown} + ${#more} + (${#more} > 0 ? 1 : 0) + (${#shown} > 0 ? 1 : 0) + ${#line} + 8 ))
  bytes=$(( bytes + ${#bytes} ))  # the digits of the count are part of the count
  line+=" bytes=$bytes"
  [[ -z "$more" ]] || echo "$more"
  echo "$line"
  record_run "$status" "${YUKTA_SCOPE:--}" "${wall:-0}" "${cost:-0}" "$bytes" "${healed:--}"
  # what the status line and the Stop hook read: the changes this result is for, and how it ended
  mkdir -p "$STATE_DIR"
  printf '%s\t%s\t%s\t%s\t%s\n' "$(changes_sum "${YUKTA_PROJECT:-$REPO_ROOT}")" "$status" "${YUKTA_SCOPE:--}" "${healed:--}" "$(date +%s)" \
    >"$STATE_DIR/last-result"
  [[ "$status" == COMPLETED || "$status" == SUCCESS ]]
}

# run_wall PROGRESS_JSON: whole seconds from the first node start to the last node end ("" when no node ran).
run_wall() {
  jq -r '[.tasks[] | select((.startTime // "0001")[:4] != "0001" and (.endTime // "0001")[:4] != "0001")
          | {s: (.startTime | sub("\\.[0-9]+"; "") | strptime("%Y-%m-%dT%H:%M:%S") | mktime), e: (.endTime | sub("\\.[0-9]+"; "") | strptime("%Y-%m-%dT%H:%M:%S") | mktime)}]
         | if length > 0 then (([.[].e] | max) - ([.[].s] | min)) else empty end' <<<"$1" 2>/dev/null || true
}

# record_run STATUS KIND WALL COST BYTES HEALED: one tab separated row per run in $STATE_DIR/runs.tsv (yk stats reads it).
record_run() {
  mkdir -p "$STATE_DIR"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$2" "$1" "$3" "$4" "$5" "$6" >>"$STATE_DIR/runs.tsv" 2>/dev/null || true
}

# filter_plan ONLY SKIP: the plan on stdin (yk plan's lines) without the stages not in ONLY (a comma list, "" = all) or in SKIP. A stage name
# matches with - or _ either way; comment lines stay.
filter_plan() {
  awk -F'\t' -v only="$1" -v skip="$2" '
    function norm(s) { gsub(/-/, "_", s); return s }
    BEGIN { n = split(only, o, ","); m = split(skip, k, ","); for (i = 1; i <= n; i++) O[norm(o[i])] = 1; for (i = 1; i <= m; i++) K[norm(k[i])] = 1 }
    /^#/ { print; next }
    { s = norm($1); if ((n == 0 || s in O) && !(s in K)) print }'
}

# failed_output SESSION EXECUTION: the whole output of the failed stage (the stdout in the payload of the terminal that ended the run FAILED).
failed_output() {
  "$(find_cli)" -o json result "$1" "$2" 2>/dev/null \
    | jq -rs '[.[] | select(.kind == "RESULT" and (.payload.outcome == "FAILED" or .payload.success == false))][0].payload | (.results[0].stdout // .stdout) // empty'
}

# heal_records SESSION: the directory with the heal records of the last check run: YUKTA_HEAL_RECORDS when set, else the session's own.
heal_records() {
  echo "${YUKTA_HEAL_RECORDS:-$STATE_DIR/heal/$1}"
}

# healed_stages SESSION [FAILED_STAGE [RECORDS_DIR]]: "stage:step,..." for every heal step of the last check
# run that fixed its stage (records written by yukta-heal.sh, step = auto or the model), except the stage
# that still failed; empty when nothing was healed.
healed_stages() {
  local dir="${3:-$(heal_records "$1")}"
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
# Seconds carry milliseconds (the server prints the fraction). ponytail: a plain median (failed fast runs count); use a percentile if noisy.
record_durations() {
  local session="$1" exec="$2" scope="$3" file="$STATE_DIR/durations.tsv"
  local wf stage secs key prior n median
  mkdir -p "$STATE_DIR"
  touch "$file"
  while IFS=$'\t' read -r wf stage secs; do
    # a stage that was not planned takes about no time: recording it would drag the median to 0
    awk -v s="$secs" 'BEGIN { exit !(s >= 2) }' || continue
    key="$wf:$scope"
    prior="$(awk -F'\t' -v k="$key" -v n="$stage" '$2 == k && $3 == n { print $4 }' "$file" | tail -10 | sort -n)"
    n="$(grep -c . <<<"$prior" || true)"
    if ((n >= 3)) && awk -v s="$secs" 'BEGIN { exit !(s > 15) }'; then
      median="$(awk '{ a[NR] = $1 } END { print a[int((NR + 1) / 2)] }' <<<"$prior")"
      if awk -v s="$secs" -v m="$median" 'BEGIN { exit !(s * 2 > m * 3) }'; then
        echo "SLOW $stage ${secs}s (median ${median}s, $n runs)"
      fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$(date -Is)" "$key" "$stage" "$secs" "$exec" >>"$file"
  done < <("$(find_cli)" -o json workflow status "$session" "$exec" | jq -r '
    def secs: ((.[:19] | strptime("%Y-%m-%dT%H:%M:%S") | mktime) + ((.[19:] | capture("^\\.(?<f>[0-9]+)") | "0." + .f | tonumber) // 0));
    def stage: sub("\\.(run[0-9]+|f[0-9]+|r[0-9]+|pause|heal)$"; "");
    (.data // .) as $p
    | [$p.tasks[] | select((.module | test("TERMINAL") | not) and (.startTime // "0001")[:4] != "0001" and (.endTime // "0001")[:4] != "0001")
       | {stage: (.nodeId | stage), secs: ((.endTime | secs) - (.startTime | secs))}]
    | group_by(.stage)[] | "\($p.workflowId)\t\(.[0].stage)\t\(map(.secs) | add * 1000 | round / 1000)"')
}

# ---- the optional decision model (System One via Ollama), see decisions.json. Everything here fails open: with no model, a stale model,
# a slow answer or too big a digest the caller takes the path it took before, and gets one WARN line.
# YUKTA_DECISION=off disables it; YUKTA_DECISION_MODEL, YUKTA_DECISION_URL, YUKTA_DECISION_MAX_INPUT override decisions.json.
_dec() { jq -r --arg k "$1" '.[$k] // empty' "$(dirname "${BASH_SOURCE[0]}")/../decisions.json"; }
decision_model() { echo "${YUKTA_DECISION_MODEL:-$(_dec model)}"; }
decision_url() { echo "${YUKTA_DECISION_URL:-$(_dec url)}"; }

# _decision_call URL BODY: prints "<http-code> <ms>" then the body of the answer (curl, 60 s at most)
_decision_call() {
  local t0 out code
  t0="$(date +%s%N)"
  out="$(curl -sS -m 60 -o - -w '\n%{http_code}' -H 'Content-Type: application/json' -d "$2" "$1/v1/systemone" 2>&1)" || { echo "000 0"; echo "$out"; return; }
  code="${out##*$'\n'}"
  echo "$code $(((($(date +%s%N) - t0)) / 1000000))"
  echo "${out%$'\n'*}"
}

# _decision_body MODEL STATE QUESTION CRITERIA-JSON: the System One request with one choice question named "q"
_decision_body() {
  jq -nc --arg m "$1" --arg s "$2" --arg q "$3" --argjson c "$4" \
    '{model: $m, state: $s, questions: {q: {type: "choice", instructions: $q, criteria: $c}}, keep_alive: "10m"}'
}

# decide STATE QUESTION CRITERIA-JSON: prints "<label> <probability>" for the model's choice. Returns 1, with a WARN on stderr, when there is
# no answer (off, no model, digest over the cap, error): the caller keeps its static path.
decide() {
  local state="$1" question="$2" criteria="$3" model url max reply head code
  [[ "${YUKTA_DECISION:-on}" != off ]] || return 1
  model="$(decision_model)" url="$(decision_url)" max="${YUKTA_DECISION_MAX_INPUT:-$(_dec maxInput)}"
  if [[ -z "$model" ]]; then echo "WARN decision model unavailable: none configured (YUKTA_DECISION_MODEL)" >&2; return 1; fi
  if ((${#state} + ${#question} > ${max:-1024})); then
    echo "WARN decision model unavailable: the digest is $((${#state} + ${#question})) characters, over the cap of ${max:-1024}" >&2
    return 1
  fi
  reply="$(_decision_call "$url" "$(_decision_body "$model" "$state" "$question" "$criteria")")"
  head="$(head -n 1 <<<"$reply")" code="${head%% *}"
  if [[ "$code" != 200 ]]; then
    echo "WARN decision model unavailable: $(_decision_reason "$code" "$(tail -n +2 <<<"$reply")" "$model")" >&2
    return 1
  fi
  tail -n +2 <<<"$reply" | jq -r '.answers.q | "\(.choice) \(.probabilities[.choice])"' 2>/dev/null \
    || { echo "WARN decision model unavailable: unreadable answer" >&2; return 1; }
}

# _decision_reason CODE BODY MODEL: what a failed call means, with the command that fixes it
_decision_reason() {
  case "$2" in
    *"no longer supported"*) echo "'$3' was downloaded in a format that is no longer supported; run: ollama pull $3" ;;
    *"not found"*) echo "model '$3' is not pulled; run: ollama pull $3" ;;
    *) if [[ "$1" == 000 ]]; then echo "Ollama is not reachable at $(decision_url) (${2:0:120}); run: ollama serve"; else echo "HTTP $1: ${2:0:160}"; fi ;;
  esac
}

# decision_probe: one line "decision: ..." saying whether the decision model works, and 0 when it does. Kept for probeCacheSeconds in
# $DEV_HOME/decision.cache so `yk status` does not ask every time.
decision_probe() {
  local model url cache="$DEV_HOME/decision.cache" ttl ver reply head code ms ms_small ms_big crit="{\"ok\":\"the probe\",\"no\":\"never\"}" line
  if [[ "${YUKTA_DECISION:-on}" == off ]]; then echo "decision: off (YUKTA_DECISION=off)"; return 1; fi
  model="$(decision_model)" url="$(decision_url)" ttl="$(_dec probeCacheSeconds)"
  if [[ -s "$cache" && "$(head -n 1 "$cache")" == "$model $url" && $(($(date +%s) - $(stat -c %Y "$cache"))) -lt ${ttl:-600} && -z "${YK_DECISION_FRESH:-}" ]]; then
    tail -n +2 "$cache"; tail -n +2 "$cache" | grep -q '^decision: ok'; return
  fi
  if [[ -z "$model" ]]; then
    line="decision: not configured; set YUKTA_DECISION_MODEL (installed: $(curl -sS -m 3 "$url/api/tags" 2>/dev/null | jq -r '[.models[].name] | join(", ")' 2>/dev/null || echo "Ollama not reachable at $url"))"
  elif ! ver="$(curl -sS -m 3 "$url/api/version" 2>/dev/null | jq -er .version)"; then
    line="decision: unavailable, Ollama is not reachable at $url; run: ollama serve"
  elif [[ "$(printf '%s\n%s\n' "$(_dec minOllama)" "$ver" | sort -V | head -n 1)" != "$(_dec minOllama)" ]]; then
    line="decision: unavailable, Ollama $ver is older than $(_dec minOllama); update Ollama"
  else
    reply="$(_decision_call "$url" "$(_decision_body "$model" "Is this a probe? Yes." "Is this a probe?" "$crit")")"
    head="$(head -n 1 <<<"$reply")" code="${head%% *}" ms="${head##* }"
    if [[ "$code" != 200 ]]; then
      line="decision: unavailable, $(_decision_reason "$code" "$(tail -n +2 <<<"$reply")" "$model")"
    else
      ms_small="$ms"
      ms_big="$(_decision_call "$url" "$(_decision_body "$model" "$(head -c 1000 /dev/zero | tr '\0' x)" "Is this a probe?" "$crit")" | sed -n 1p)"
      ms_big="${ms_big##* }"
      line="decision: ok $model at $url (Ollama $ver), ${ms_small} ms for a small digest, $((ms_big > ms_small ? ms_big - ms_small : 0)) ms per KB"
      ((ms_small > $(_dec slowMs))) && line="$line; WARN slower than $(_dec slowMs) ms, keep it off the green path"
    fi
  fi
  mkdir -p "$DEV_HOME" 2>/dev/null && printf '%s %s\n%s\n' "$model" "$url" "$line" >"$cache" 2>/dev/null || true
  echo "$line"
  [[ "$line" == "decision: ok"* ]]
}
