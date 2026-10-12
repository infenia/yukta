#!/usr/bin/env bash
# Use case: develop Yukta with Yukta. Runs this repo's quality gates as a Yukta workflow.
# Default: the quick-check workflow (formatting + Go vet). FULL=1 runs the whole gradle-check.
# The target server is YUKTA_URL, else the one started by scripts/yukta-worktree.sh.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(git -C "$here" rev-parse --show-toplevel)"
state="$repo/.yukta-dev/current.env"
if [[ -z "${YUKTA_URL:-}" && -f "$state" ]]; then
  YUKTA_URL="$(sed -n 's/^YUKTA_SERVER_URL=//p' "$state")"
  export YUKTA_URL
fi
source "$here/../lib/common.sh"
require_server

# The workflow runs gradle in the checkout, so point it at this repo.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
sed "s|__PROJECT_PATH__|$repo|g" "$here/session.json" >"$tmp/session.json"
apply_session "$tmp/session.json"

if [[ "${FULL:-0}" == 1 ]]; then
  WAIT_SECONDS=3600 run_workflow "$tmp" gradle-check SUCCESS "success=true"
else
  WAIT_SECONDS=300 run_workflow "$tmp" quick-check SUCCESS "success=true"
fi
