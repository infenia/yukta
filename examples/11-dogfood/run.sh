#!/usr/bin/env bash
# Use case: develop Yukta with Yukta. Runs this repo's quality gates as a Yukta workflow.
# Default: the quick-check workflow (formatting + Go vet). FULL=1 runs the whole gradle-check.
# The target server is YUKTA_URL, else the dev server of `yk` (127.0.0.1:9858) when it is up, else localhost:8080.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/scripts/_lib.sh"
if [[ -z "${YUKTA_URL:-}" ]] && healthy; then
  export YUKTA_URL="$DEV_URL"
  export YUKTA_API_KEY="${YUKTA_API_KEY:-$(dev_key)}"
fi
source "$here/../lib/common.sh"
require_server

# The workflow runs gradle in the checkout, so point it at this repo.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
render_session "$here/session.json" "$REPO_ROOT" >"$tmp/session.json"
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
