#!/usr/bin/env bash
# pickup.sh tick: one round of the scheduled pickup (workflow `pickup`, `yk pickup now`). Hands issues to the yukta-pickup channels
# (channel/yukta-pickup.py) of the open `yk pickup session`s, one issue per idle session:
#   1. sessions: the registry $DEV_HOME/pickup/*.json (a dead pid is dropped); each must answer GET /status and be idle
#                                                           -> else "PICKUP skipped=<why>"
#   2. issues: assigned to the user AND Ready on the project, no open PR naming it, no issue/<n> branch, top Priority first (P0 first)
#   3. POST /issue with the token to the first idle session  -> "PICKUP handed=#<n> to=<name>" (one line per issue handed)
# Exit 0 for every skip; non-zero only for a real error (gh not signed in, an answer that is not JSON, a refused POST).
# Env: YUKTA_PICKUP_ONLY (hand only to the session of that name), YUKTA_PICKUP_REPO (infenia/yukta), YUKTA_PICKUP_PROJECT (5),
# YUKTA_PICKUP_OWNER (infenia), YUKTA_DEV_HOME.
set -euo pipefail
DEV_HOME="${YUKTA_DEV_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/yukta-dev}"
REPO="${YUKTA_PICKUP_REPO:-infenia/yukta}"
PROJECT="${YUKTA_PICKUP_PROJECT:-5}"
OWNER="${YUKTA_PICKUP_OWNER:-infenia}"
ONLY="${YUKTA_PICKUP_ONLY:-}"

fail() { echo "PICKUP error: $*" >&2; exit 1; }
skip() { echo "PICKUP skipped=$*"; exit 0; }

# sessions: fills idle=("name port" ...), oldest first, and busy_names; deletes the entries of dead channels
sessions() {
  local f name port pid st n=0 files=()
  idle=() busy_names=()
  shopt -s nullglob
  files=("$DEV_HOME"/pickup/*.json)
  shopt -u nullglob
  while IFS=$'\t' read -r _ f; do
    [[ -n "$f" ]] || continue
    IFS=$'\t' read -r name port pid < <(jq -r '[.name, .port, .pid] | @tsv' "$f" 2>/dev/null) || { rm -f "$f"; continue; }
    [[ "$pid" =~ ^[0-9]+$ && "$port" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null || { rm -f "$f"; continue; }
    [[ -z "$ONLY" || "$name" == "$ONLY" ]] || continue
    n=$((n + 1))
    if st="$(curl -fsS -m 5 "http://127.0.0.1:$port/status" 2>/dev/null)" && jq -e '.busy == false' <<<"$st" >/dev/null 2>&1; then
      idle+=("$name $port")
    else
      busy_names+=("$name")
    fi
  done < <(for f in "${files[@]}"; do printf '%s\t%s\n' "$(jq -r '.started // 0' "$f" 2>/dev/null || echo 0)" "$f"; done | sort -n)
  registered=$n
}

tick() {
  local mine ready open_prs n title url ref body code name port handed=0
  local idle=() busy_names=() registered=0
  sessions
  ((registered > 0)) || skip "no channel${ONLY:+ named $ONLY} (open one with: yk pickup session)"
  ((${#idle[@]} > 0)) || skip "all busy ($(IFS=,; echo "${busy_names[*]}"))"

  mine="$(gh issue list -R "$REPO" --assignee @me --state open --limit 200 --json number,title,url)" || fail "gh issue list"
  ready="$(gh project item-list "$PROJECT" --owner "$OWNER" --limit 5000 --format json)" || fail "gh project item-list"
  open_prs="$(gh pr list -R "$REPO" --state open --limit 200 --json headRefName,title,body)" || fail "gh pr list"
  jq -e . >/dev/null <<<"$mine$ready$open_prs" 2>&1 || fail "gh answered something that is not JSON"
  [[ -s "$DEV_HOME/pickup-token" ]] || fail "no $DEV_HOME/pickup-token (yk pickup session creates it)"

  # assigned AND Ready, highest Priority first (P0 before P3; no priority last), then the lowest number
  while IFS=$'\t' read -r n title url; do
    ((${#idle[@]} > 0)) || break
    # an open PR that names the issue, or its branch on the remote: someone is on it
    if jq -e --argjson n "$n" '[.[] | select(.headRefName == "issue/\($n)" or ((.title + " " + (.body // "")) | test("(?i)(fix(es|ed)?|close[sd]?|resolve[sd]?|refs?) #\($n)\\b")))] | length > 0' <<<"$open_prs" >/dev/null; then
      continue
    fi
    ref="$(gh api "repos/$REPO/git/matching-refs/heads/issue/$n")" || fail "gh api matching-refs"
    jq -e 'length > 0' <<<"$ref" >/dev/null 2>&1 && continue
    body="$(jq -nc --argjson n "$n" --arg t "$title" --arg u "$url" '{number: $n, title: $t, url: $u}')"
    # the first idle session that takes it; one that answers busy meanwhile is out for this round
    while ((${#idle[@]} > 0)); do
      read -r name port <<<"${idle[0]}"
      code="$(curl -sS -m 10 -o /dev/null -w '%{http_code}' -X POST -H "X-Pickup-Token: $(cat "$DEV_HOME/pickup-token")" -d "$body" "http://127.0.0.1:$port/issue")" \
        || fail "POST to $name (:$port) failed"
      idle=("${idle[@]:1}")
      case "$code" in
        202) echo "PICKUP handed=#$n to=$name"; handed=$((handed + 1)); break ;;
        409) busy_names+=("$name") ;;
        *) fail "POST to $name (:$port) answered $code" ;;
      esac
    done
  done < <(jq -rn --argjson mine "$mine" --argjson ready "$ready" '
    ([$ready.items[]? | select(.status == "Ready") | {key: (.content.number | tostring), value: (.priority // "P9")}] | from_entries) as $pri
    | [$mine[] | select($pri[(.number | tostring)] != null) | . + {p: $pri[(.number | tostring)]}]
    | sort_by(.p, .number)[] | [.number, .title, .url] | @tsv')
  ((handed > 0)) || skip "nothing assigned to you is Ready, or each such issue has a PR or an issue/<n> branch"
}

case "${1:-}" in
  tick) command -v gh >/dev/null && command -v jq >/dev/null && command -v curl >/dev/null || fail "gh, jq and curl are required"; tick ;;
  *) echo "usage: pickup.sh tick" >&2; exit 2 ;;
esac
