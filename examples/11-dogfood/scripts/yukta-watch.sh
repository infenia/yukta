#!/usr/bin/env bash
# Follow a Yukta execution with control-bus events until it ends, then print the RESULT line.
# A thin wrapper around `yukta-cli controlbus watch`; meant to run under Claude Code's Monitor tool.
#
# Usage: [YUKTA_SCOPE=<module|all>] yukta-watch.sh <session-id> <execution-id|check-log-file> [--quiet | --no-logs]
#
# One line per event:
#   NODE <node> <status>   WORKFLOW <status>   CONTROL <command> applied=<bool> [reason]   LOG <node>: <line>
# With --quiet (what an agent should use) only what needs attention is printed, so a run that goes
# well costs a few lines: the NODE line of a failed node followed by
#   ERROR <node>: <line>   WARN <node>: <line>
# (the failure reason, and the error/warning lines of its output), CONTROL lines that were not applied,
# and WORKFLOW <status> at the end.
# then, after the end of the run:
#   SLOW <node> <s>s (median <m>s, <n> runs)     (zero or more, see record_durations in _lib.sh)
#   RESULT status=<STATUS> failed_node=<node|-> execution=<id> session=<id>
# Exit code: 0 only when the execution ends COMPLETED/SUCCESS.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

need jq
session="${1:?usage: yukta-watch.sh <session-id> <execution-id> [--quiet | --no-logs]}"
exec_id="${2:?usage: yukta-watch.sh <session-id> <execution-id> [--quiet | --no-logs]}"
shift 2

# A staged check (yukta-check.sh --detach) gives a log file instead of an execution: follow it to its
# RESULT line.
if [[ -f "$exec_id" ]]; then
  tail -n +1 -f "$exec_id" | awk '{ print; fflush() } /^RESULT / { exit }' || true
  grep -q '^RESULT status=COMPLETED' "$exec_id"
  exit $?
fi

load_server
"$(find_cli)" controlbus watch --execution "$exec_id" --until-done "$@"
record_durations "$session" "$exec_id" "${YUKTA_SCOPE:--}"
print_result "$session" "$exec_id"
