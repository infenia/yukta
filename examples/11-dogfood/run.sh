#!/usr/bin/env bash
# Use case: develop Yukta with Yukta. Runs this repo's quality gates as a Yukta workflow.
# Default: the quick-check workflow (formatting + Go vet). FULL=1 runs the whole gradle-check.
# The target server is YUKTA_URL, else the one started by yukta-server.sh, else the running one on localhost:8080.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(git -C "$here" rev-parse --show-toplevel)"
state="$repo/.yukta-dev/current.env"
if [[ -z "${YUKTA_URL:-}" && -f "$state" ]]; then
  YUKTA_URL="$(sed -n 's/^YUKTA_SERVER_URL=//p' "$state")"
  export YUKTA_URL
fi
source "$here/../lib/common.sh"
source "$here/scripts/_lib.sh"
require_server

# The workflow runs gradle in the checkout, so point it at this repo.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
render_session "$here/session.json" "$repo" >"$tmp/session.json"
apply_session "$tmp/session.json"

wf=quick-check
[[ "${FULL:-0}" == 1 ]] && wf=gradle-check
# Wait as long as the stages may run (sum of their timeouts) plus a margin, so a slow stage is
# never reported as a failure while the execution is still within its limits.
WAIT_SECONDS=$(jq --arg w "$wf" \
  '[.workflows[$w].nodes[] | select(.type == "PROCESS_EXECUTOR") | .config.timeoutSeconds] | add + 30' \
  "$tmp/session.json")
export WAIT_SECONDS
run_workflow "$tmp" "$wf" SUCCESS "success=true"
