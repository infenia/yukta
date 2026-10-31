#!/usr/bin/env bash
# pickup.sh tick: one round of the scheduled pickup (workflow `pickup`, `yk pickup now`). Hands the next issue to the yukta-pickup channel
# (channel/yukta-pickup.py) of an open `yk pickup session`:
#   1. the channel must answer GET /status and be idle      -> else "PICKUP skipped=<why>"
#   2. pick the issue: assigned to the user AND Ready on the project, no open PR for it, no issue/<n> branch, top Priority (P0 first)
#   3. POST /issue with the token                           -> "PICKUP handed=#<n>"
# One PICKUP line, exit 0 for every skip; non-zero only for a real error (gh not signed in, an answer that is not JSON, a refused POST).
# Env: YUKTA_PICKUP_PORT (8790), YUKTA_PICKUP_REPO (infenia/yukta), YUKTA_PICKUP_PROJECT (5), YUKTA_PICKUP_OWNER (infenia), YUKTA_DEV_HOME.
set -euo pipefail
DEV_HOME="${YUKTA_DEV_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/yukta-dev}"
URL="http://127.0.0.1:${YUKTA_PICKUP_PORT:-8790}"
REPO="${YUKTA_PICKUP_REPO:-infenia/yukta}"
PROJECT="${YUKTA_PICKUP_PROJECT:-5}"
OWNER="${YUKTA_PICKUP_OWNER:-infenia}"

fail() { echo "PICKUP error: $*" >&2; exit 1; }
skip() { echo "PICKUP skipped=$*"; exit 0; }

tick() {
  local st mine ready open_prs n title url ref body code
  st="$(curl -fsS -m 5 "$URL/status" 2>/dev/null)" || skip "no channel on $URL (open one with: yk pickup session)"
  jq -e '.busy == false' <<<"$st" >/dev/null 2>&1 || skip "busy with #$(jq -r '.issue // "?"' <<<"$st" 2>/dev/null)"

  mine="$(gh issue list -R "$REPO" --assignee @me --state open --limit 200 --json number,title,url)" || fail "gh issue list"
  ready="$(gh project item-list "$PROJECT" --owner "$OWNER" --limit 5000 --format json)" || fail "gh project item-list"
  open_prs="$(gh pr list -R "$REPO" --state open --limit 200 --json headRefName,title,body)" || fail "gh pr list"
  jq -e . >/dev/null <<<"$mine$ready$open_prs" 2>&1 || fail "gh answered something that is not JSON"

  # assigned AND Ready, highest Priority first (P0 before P3; no priority last), then the lowest number
  while IFS=$'\t' read -r n title url; do
    # an open PR that names the issue, or its branch on the remote: someone is on it
    if jq -e --argjson n "$n" '[.[] | select(.headRefName == "issue/\($n)" or ((.title + " " + (.body // "")) | test("(?i)(fix(es|ed)?|close[sd]?|resolve[sd]?|refs?) #\($n)\\b")))] | length > 0' <<<"$open_prs" >/dev/null; then
      continue
    fi
    ref="$(gh api "repos/$REPO/git/matching-refs/heads/issue/$n")" || fail "gh api matching-refs"
    jq -e 'length > 0' <<<"$ref" >/dev/null 2>&1 && continue
    body="$(jq -nc --argjson n "$n" --arg t "$title" --arg u "$url" '{number: $n, title: $t, url: $u}')"
    [[ -s "$DEV_HOME/pickup-token" ]] || fail "no $DEV_HOME/pickup-token (yk pickup session creates it)"
    code="$(curl -sS -m 10 -o /dev/null -w '%{http_code}' -X POST -H "X-Pickup-Token: $(cat "$DEV_HOME/pickup-token")" -d "$body" "$URL/issue")" \
      || fail "POST $URL/issue failed"
    case "$code" in
      202) echo "PICKUP handed=#$n"; exit 0 ;;
      409) skip "busy (the channel took another issue meanwhile)" ;;
      *) fail "POST $URL/issue answered $code" ;;
    esac
  done < <(jq -rn --argjson mine "$mine" --argjson ready "$ready" '
    ([$ready.items[]? | select(.status == "Ready") | {key: (.content.number | tostring), value: (.priority // "P9")}] | from_entries) as $pri
    | [$mine[] | select($pri[(.number | tostring)] != null) | . + {p: $pri[(.number | tostring)]}]
    | sort_by(.p, .number)[] | [.number, .title, .url] | @tsv')
  skip "nothing assigned to you is Ready, or each such issue has a PR or an issue/<n> branch"
}

case "${1:-}" in
  tick) command -v gh >/dev/null && command -v jq >/dev/null && command -v curl >/dev/null || fail "gh, jq and curl are required"; tick ;;
  *) echo "usage: pickup.sh tick" >&2; exit 2 ;;
esac
