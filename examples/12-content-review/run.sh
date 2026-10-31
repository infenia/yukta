#!/usr/bin/env bash
# Use case: a marketing draft goes through rule checks, then an editor approves it, asks for
# changes (up to 3 rounds) or rejects it. This script plays the editor through the signal API.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
SID=$(jq -r .sessionId session.json)
OUT=/tmp/yukta-examples/content-review   # the baseDir used by the FILE_WRITE nodes in session.json
ANSWERED=" "

# start_run WORKFLOW PAYLOAD_FILE -> prints the execution id
start_run() {
  local body
  body=$(jq -n --arg s "$SID" --arg w "$1" --slurpfile p "$2" '{sessionId: $s, workflowId: $w, payload: $p[0]}')
  api POST /api/workflow/start "$body" | jq -r .data.executionId
}

# signal EXECUTION NODE NAME DECISION [DATA]: what the editor's button does.
signal() {
  local q="name=$3&decision=$4"
  [[ -n "${5:-}" ]] && q+="&data=$(jq -rn --arg d "$5" '$d|@uri')"
  api POST "/api/executions/$1/nodes/$2/signal?$q" >/dev/null
}

# answer NAME DECISION [DATA]: wait for the next open approval called NAME, then answer it.
answer() {
  local name=$1 row eid node i
  for ((i = 0; i < 60; i++)); do
    row=$(api GET "/api/sessions/$SID/approvals" | jq -c --arg n "$name" '[.data[] | select(.name == $n)][]' | while read -r r; do
      e=$(jq -r .executionId <<<"$r"); [[ "$ANSWERED" == *" $e "* ]] && continue
      # only approvals that belong to this run (a round is a child execution of RUN)
      [[ "$(api GET "/api/executions/$e" | jq -r .data.rootExecutionId)" == "$RUN" ]] && { echo "$r"; break; }
    done)
    [[ -n "$row" ]] && break
    sleep 0.5
  done
  [[ -n "$row" ]] || { echo "    FAIL: no pending approval '$name'"; return 1; }
  eid=$(jq -r .executionId <<<"$row"); node=$(jq -r .nodeId <<<"$row")
  ANSWERED+="$eid "
  echo "    editor: $2${3:+ ($3)} on $node of $eid"
  signal "$eid" "$node" "$name" "$2" "${3:-}"
}

# finish EXECUTION: wait for the run to end; sets STATUS and LOGS.
finish() {
  local i
  for ((i = 0; i < 60; i++)); do
    STATUS=$(api GET "/api/executions/$1" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 "$YUKTA_URL/api/sessions/$SID/executions/$1/logs" | sed -n 's/^data://p' || true)
}

# expect LABEL CONDITION...: PASS/FAIL line for one assertion
FAILED=0
expect() {
  local label=$1; shift
  if "$@"; then echo "    ok: $label"; else echo "    FAIL: $label"; FAILED=1; fi
}
outbox_has() { grep -qF -- "$1" "$OUT/outbox.log" 2>/dev/null; }
page_has() { grep -qF -- "$2" "$OUT/site/$1" 2>/dev/null; }
no_page() { [[ -z "$(ls "$OUT/site" 2>/dev/null)" ]]; }

# scenario NAME PAYLOAD_JSON: starts the review with a fresh output folder
scenario() {
  echo "--> $1"
  rm -rf "$OUT"; mkdir -p "$OUT"
  jq -n "$2" > "$OUT/payload.json"
  RUN=$(start_run review "$OUT/payload.json")
  echo "    curl -X POST $YUKTA_URL/api/workflow/start -H 'Content-Type: application/json' -d '$(jq -nc --arg s "$SID" --slurpfile p "$OUT/payload.json" '{sessionId: $s, workflowId: "review", payload: $p[0]}')'"
}

apply_session session.json

scenario "clean draft, editor approves" '{title: "Spring Sale", slug: "spring-sale", pitch: "Save on all widgets this spring."}'
answer review approve
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "page published" page_has spring-sale.md "Save on all widgets this spring."
expect "team notified" outbox_has "PUBLISHED: Spring Sale"

scenario "banned words are caught before the editor sees them" '{title: "Widget Launch", slug: "widget-launch", pitch: "A guaranteed miracle for your shelf."}'
answer review approve
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "round 1 failed the automatic check" grep -q "automatic check failed: banned words" <<<"$LOGS"
expect "published text is the revision" page_has widget-launch.md "revision 2"
expect "banned word is gone" bash -c "! grep -qi guaranteed '$OUT/site/widget-launch.md'"

scenario "editor asks for changes, notes reach the next draft" '{title: "Autumn Offer", slug: "autumn-offer", pitch: "Warm deals for cold days."}'
answer review changes "shorter headline, add a date"
answer review approve
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "notes carried into round 2" grep -qE "start-round-2/MAPPER.*notes=shorter headline, add a date" <<<"$LOGS"
expect "round 2 was published" page_has autumn-offer.md "revision 2"

scenario "editor keeps asking for changes: stop after 3 rounds" '{title: "Winter Deal", slug: "winter-deal", pitch: "Snow prices on every item."}'
answer review changes "more specific"
answer review changes "still vague"
answer review changes "try again"
finish "$RUN"
expect "run ended normally (outcome FAILED, not a crash)" [ "$STATUS" = SUCCESS ]
expect "team told it needs a human writer" outbox_has "not approved after 3 rounds"
expect "nothing published" no_page

scenario "editor rejects the piece" '{title: "Off Brand", slug: "off-brand", pitch: "Something the editor does not want."}'
answer review reject
finish "$RUN"
expect "team told it was rejected" outbox_has "the editor rejected the piece"
expect "nothing published" no_page

scenario "too long: only the automatic check runs, no editor involved" '{title: "Long Story", slug: "long-story", pitch: "This pitch is a good deal longer than the tiny limit.", maxWords: 5}'
finish "$RUN"
expect "length rule fired" grep -q "too long" <<<"$LOGS"
expect "team told it needs a human writer" outbox_has "not approved after 3 rounds"

scenario "invalid brief is refused up front" '{title: "No pitch here", slug: "none"}'
finish "$RUN"
expect "team told the brief is invalid" outbox_has "the brief is invalid"

rm -rf "$OUT"
if (( FAILED )); then echo "FAIL 12-content-review"; exit 1; fi
echo "PASS 12-content-review: 7 paths"
