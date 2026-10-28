#!/usr/bin/env bash
# Offline test of the result events: only a finished run gets one, a run gets exactly one, and a stopped run is recorded.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export YUKTA_HEAL_EVENTS="$tmp/events.jsonl"
# shellcheck source=_lib.sh
source "$here/scripts/_lib.sh"
progress='{"workflowId":"check","tasks":[]}'
count() { [[ -f "$YUKTA_HEAL_EVENTS" ]] && jq -s --arg r "$1" '[.[] | select(.event == "result" and .run == $r)] | length' "$YUKTA_HEAL_EVENTS" || echo 0; }

# a run that is still going has no result
for status in RUNNING PENDING QUEUED PAUSED; do emit_result s run-live "$status" - - "$progress"; done
[[ "$(count run-live)" == 0 ]] || fail "a non-terminal status got a result"

# the same run reported twice (yk watch, then yk result) is one result, the first
emit_result s run-1 SUCCESS - - "$progress"
emit_result s run-1 FAILED tests - "$progress"
[[ "$(count run-1)" == 1 ]] || fail "run-1 has $(count run-1) results"
[[ "$(jq -r 'select(.run == "run-1") | .status' "$YUKTA_HEAL_EVENTS")" == SUCCESS ]] || fail "the first result is kept"

# another run is not affected, and a stopped run is a result with a terminal status
emit_result s run-2 STOPPED - - '{"tasks":[]}'
[[ "$(count run-2)" == 1 && "$(jq -r 'select(.run == "run-2") | .status' "$YUKTA_HEAL_EVENTS")" == STOPPED ]] || fail "run-2: $(count run-2)"
# a run id that merely contains another id does not count as seen
emit_result s run-22 SUCCESS - - "$progress"
[[ "$(count run-22)" == 1 ]] || fail "run-22 was taken for run-2"
echo "ok   events"
