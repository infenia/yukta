#!/usr/bin/env bash
# Run the staged `gradle check` workflow on a Yukta server and report the result.
#
# Usage: [WORKFLOW=quick-check] yukta-check.sh [project-path]
#   project-path  checkout to check (default: current git toplevel)
#   WORKFLOW      gradle-check (default, full) or quick-check (formatting + Go vet)
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
# The path hash keeps checkouts apart on a shared server (feature/a vs feature-a, two clones).
session="check-$branch-$(printf '%s' "$project" | sha1sum | cut -c1-8)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Point the template at this checkout and give the session its own id.
render_session "$REPO_ROOT/examples/11-dogfood/session.json" "$project" \
  | jq --arg s "$session" '.sessionId=$s | .name=$s' >"$tmp/session.json"

echo ">> server $YUKTA_SERVER_URL, session $session, project $project"
"$cli" session apply "$tmp/session.json" >/dev/null
exec_id="$("$cli" -o json workflow start "$session" "${WORKFLOW:-gradle-check}" | jq -r '.data.executionId // .executionId')"
[[ -n "$exec_id" && "$exec_id" != null ]] || die "could not start workflow"
echo ">> execution $exec_id"

# The log stream can 404 for a moment right after start; retry briefly.
for _ in 1 2 3 4 5; do
  "$cli" logs "$session" "$exec_id" && break
  sleep 2
done

progress="$("$cli" -o json workflow status "$session" "$exec_id" | jq '(.data // .)')"
status="$(jq -r '.status' <<<"$progress")"
failed="$(jq -r '[.tasks[] | select(.status | test("FAIL|ERROR"; "i")) | .nodeId][0] // "-"' <<<"$progress")"

echo "RESULT status=$status failed_node=$failed execution=$exec_id session=$session"
[[ "$status" == COMPLETED || "$status" == SUCCESS ]]
