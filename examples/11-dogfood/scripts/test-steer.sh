#!/usr/bin/env bash
# Online test of the steering commands (yk tree, pause, resume, stop, signals, approve, reject) against the
# dev server, with a throwaway project (fake-project.sh). Needs a running server (yk up).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
yk="$here/../yk"
fail() { echo "FAIL $*" >&2; exit 1; }

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
"$here/fake-project.sh" "$dir"
proj="$dir/proj"
export YUKTA_CLAUDE_BIN="$dir/claude" YUKTA_CHECK_EXTRAS=off YUKTA_TRIAGE=off YUKTA_HEAL_EVENTS=off YUKTA_PAUSE_SECONDS=120

exec_id="" session=""
# start FLAGS...: a detached check of the fake project; sets exec_id and session
start() {
  local out line
  out="$("$yk" check -p "$proj" --detach "$@" 2>&1)" || { echo "$out" >&2; fail "could not start"; }
  line="$(grep '^STARTED' <<<"$out")"
  exec_id="${line#*execution=}"; exec_id="${exec_id%% *}"
  session="${line##*session=}"
}
reset() { rm -f "$proj"/fail-* "$proj"/slow-* "$proj/.fake-fix-at" "$proj/fixed.txt"; for f in "$@"; do touch "$proj/$f"; done; }
# wait SECONDS COMMAND...: until the command succeeds
wait_for() {
  local n="$1"
  shift
  for ((i = 0; i < n * 2; i++)); do "$@" && return 0; sleep 0.5; done
  return 1
}
top() { "$yk" tree "${exec_id:0:8}" | head -1; }
top_has() { top | grep -Eq "$1"; }
signals_have() { "$yk" signals "${exec_id:0:8}" | grep -q "$1"; }
result() { "$yk" watch "$session" "$exec_id" 2>&1 | tail -1 || true; }

# --- pause and resume the tree of a slow run, which then ends well
reset slow-compileJava
start
wait_for 20 top_has "check RUNNING" || fail "the run never showed as RUNNING: $(top)"
wait_for 20 top_has "compile.run1" || fail "tree does not show the current node: $(top)"
"$yk" pause "${exec_id:0:8}" --tree >/dev/null
wait_for 20 top_has "check RUNNING paused" || fail "pause --tree did not pause the run: $(top)"
"$yk" resume "${exec_id:0:8}" --tree >/dev/null
wait_for 20 top_has "check RUNNING \[" || fail "resume --tree did not resume the run: $(top)"
result | grep -q "status=SUCCESS" || fail "the resumed run did not succeed"
echo "ok   tree, pause and resume"

# --- stop
reset slow-compileJava
start
wait_for 20 top_has "check RUNNING" || fail "no run to stop"
"$yk" stop "${exec_id:0:8}" >/dev/null
wait_for 30 top_has "check (CANCELLED|STOPPED|FAILURE)" || fail "stop did not end the run: $(top)"
echo "ok   stop"

# --- approve and reject a before-opus checkpoint
reset fail-compileJava
echo opus/low >"$proj/.fake-fix-at"
start --pause before-opus
wait_for 60 signals_have before-opus || fail "the before-opus checkpoint never waited"
"$yk" tree "${exec_id:0:8}" | grep -q "awaiting before-opus@pause-opus" || fail "tree does not show the pending signal"
"$yk" approve "${exec_id:0:8}" before-opus >/dev/null
got="$(result)"
grep -Eq "status=SUCCESS .*healed=compile:opus/low" <<<"$got" || fail "approved run did not heal at the opus rung: $got"
echo "ok   approve"

reset fail-compileJava
echo opus/low >"$proj/.fake-fix-at"
start --pause before-opus
wait_for 60 signals_have before-opus || fail "the before-opus checkpoint never waited (reject)"
"$yk" reject "${exec_id:0:8}" before-opus >/dev/null
got="$(result)"
grep -Eq "status=FAILURE failed_node=compile " <<<"$got" || fail "rejected run did not end at compile: $got"
echo "ok   reject"

# --- yk watch says when a checkpoint waits for an answer
reset fail-compileJava
echo opus/low >"$proj/.fake-fix-at"
log="$dir/watch.out"
"$yk" check -p "$proj" --pause before-opus >"$log" 2>&1 &
checker=$!
wait_for 90 grep -q "APPROVAL before-opus waits at pause-opus" "$log" || fail "watch did not announce the checkpoint: $(tail -5 "$log")"
e8="$(grep -o 'yk approve [0-9a-f]\{8\}' "$log" | head -1 | awk '{print $3}')"
"$yk" approve "$e8" before-opus >/dev/null
wait "$checker" || fail "the watched check did not succeed: $(tail -3 "$log")"
tail -1 "$log" | grep -Eq "status=SUCCESS .*healed=compile:opus/low" || fail "the watched check did not heal at the opus rung: $(tail -2 "$log")"
echo "ok   watch announces a checkpoint"

# --- a prefix that matches no run is an error with a message
got="$("$yk" pause 00000000 2>&1 || true)"
grep -q "no single execution" <<<"$got" || fail "an unknown prefix is not reported: $got"
echo "ok   steer"
