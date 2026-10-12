#!/usr/bin/env bash
# Run the staged `gradle check` workflow on a Yukta server and report the result.
#
# Usage: yukta-check.sh [project-path]
#   project-path  checkout to check (default: current git toplevel)
#
# Server: $YUKTA_SERVER_URL, else .yukta-dev/current.env written by yukta-worktree.sh.
# Exit code: 0 only when the execution ends COMPLETED/SUCCESS. The last line is machine readable:
#   RESULT status=<STATUS> failed_node=<node|-> execution=<id> session=<id>
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

need jq
project="$(cd "${1:-$(git rev-parse --show-toplevel)}" && pwd)"

if [[ -z "${YUKTA_SERVER_URL:-}" && -f "$STATE_DIR/current.env" ]]; then
  # shellcheck disable=SC1091
  source "$STATE_DIR/current.env"
fi
export YUKTA_SERVER_URL="${YUKTA_SERVER_URL:-http://localhost:8080}"
cli="$(find_cli)"

branch="$(git -C "$project" rev-parse --abbrev-ref HEAD | tr -c 'A-Za-z0-9\n' '-')"
session="check-$branch"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Point the template at this checkout and give the session a per-branch id.
sed "s|__PROJECT_PATH__|$project|g" "$REPO_ROOT/examples/dogfood/workflows/check.session.json" \
  | jq --arg s "$session" '.sessionId=$s | .name=$s' >"$tmp/session.json"

echo ">> server $YUKTA_SERVER_URL, session $session, project $project"
"$cli" session apply "$tmp/session.json" >/dev/null
exec_id="$("$cli" -o json workflow start "$session" gradle-check | jq -r '.data.executionId // .executionId')"
[[ -n "$exec_id" && "$exec_id" != null ]] || die "could not start workflow"
echo ">> execution $exec_id"

"$cli" logs "$session" "$exec_id" || true

progress="$("$cli" -o json workflow status "$session" "$exec_id" | jq '(.data // .)')"
status="$(jq -r '.status' <<<"$progress")"
failed="$(jq -r '[.tasks[] | select(.status | test("FAIL|ERROR"; "i")) | .nodeId][0] // "-"' <<<"$progress")"

echo "RESULT status=$status failed_node=$failed execution=$exec_id session=$session"
[[ "$status" == COMPLETED || "$status" == SUCCESS ]]
