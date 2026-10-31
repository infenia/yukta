#!/usr/bin/env bash
# Use case: screen a batch of applications. Each one is validated, stripped of personal and protected
# attributes, scored with fixed weights, and (unless it is far below the bar) decided by a recruiter.
# This script plays the recruiter through the signal API.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
SID=$(jq -r .sessionId session.json)
OUT=/tmp/yukta-examples/recruiting   # the baseDir used by the FILE_WRITE nodes in session.json
FAILED=0
ANSWERED=" "

start_run() {
  api POST /api/workflow/start "$(jq -n --arg s "$SID" --slurpfile p "$1" '{sessionId: $s, workflowId: "batch", payload: $p[0]}')" | jq -r .data.executionId
}

# answer DECISION: wait for the next open "decision" approval of this batch and answer it.
# Applications are screened one at a time, so approvals arrive in batch order.
answer() {
  local row e i
  for ((i = 0; i < 80; i++)); do
    row=""
    while read -r r; do
      [[ -n "$r" ]] || continue
      e=$(jq -r .executionId <<<"$r")
      [[ "$ANSWERED" == *" $e "* ]] && continue
      [[ "$(api GET "/api/executions/$e" | jq -r .data.rootExecutionId)" == "$RUN" ]] && { row=$r; break; }
    done < <(api GET "/api/sessions/$SID/approvals" | jq -c '.data[] | select(.name == "decision")')
    [[ -n "$row" ]] && break
    sleep 0.5
  done
  [[ -n "$row" ]] || { echo "    FAIL: no pending approval"; return 1; }
  e=$(jq -r .executionId <<<"$row"); ANSWERED+="$e "
  CHILD_RUNS+=("$e")
  echo "    recruiter: $1 on $(jq -r .nodeId <<<"$row") of $e"
  api POST "/api/executions/$e/nodes/$(jq -r .nodeId <<<"$row")/signal?name=decision&decision=$1" >/dev/null
}

finish() {
  local i
  for ((i = 0; i < 90; i++)); do
    STATUS=$(api GET "/api/executions/$1" 2>/dev/null | jq -r .data.status || true)
    [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done
  LOGS=$(curl -s -m 15 "$YUKTA_URL/api/sessions/$SID/executions/$1/logs" | sed -n 's/^data://p' || true)
}

expect() {
  local label=$1; shift
  if "$@"; then echo "    ok: $label"; else echo "    FAIL: $label"; FAILED=1; fi
}
file_has() { grep -qF -- "$2" "$OUT/$1" 2>/dev/null; }
file_lacks() { ! grep -qF -- "$2" "$OUT/$1" 2>/dev/null; }

apply_session session.json
rm -rf "$OUT"; mkdir -p "$OUT"
CHILD_RUNS=()

echo "--> batch of 6 applications"
echo "    curl -X POST $YUKTA_URL/api/workflow/start -H 'Content-Type: application/json' -d '$(jq -nc --arg s "$SID" --slurpfile p batch.json '{sessionId: $s, workflowId: "batch", payload: $p[0]}')'"
RUN=$(start_run batch.json)
# A-101 strong, A-102 and A-103 in the middle, A-106 strong; A-104 is far below the bar and A-105 is invalid.
answer invite
answer pool
answer decline
answer invite
finish "$RUN"
expect "run succeeded" [ "$STATUS" = SUCCESS ]
expect "A-101 invited" file_has invitations.txt "INVITE A-101"
expect "A-106 invited" file_has invitations.txt "INVITE A-106"
expect "A-102 in the talent pool" file_has talent-pool.txt "POOL A-102"
expect "A-103 declined by the recruiter" file_has declines.txt "DECLINE A-103"
expect "A-104 declined without a recruiter" file_has declines.txt "DECLINE A-104"
expect "A-105 invalid, in no outcome file" bash -c "! grep -rq A-105 '$OUT'"
expect "summary counts every application" file_has summary.txt "SUMMARY: 6 applications, invite=2 pool=1 decline=2 invalid=1"

# The recruiter's node must not have seen protected attributes or contact details.
SEEN=$(for c in "${CHILD_RUNS[@]}"; do curl -s -m 15 "$YUKTA_URL/api/sessions/$SID/executions/$c/logs" | sed -n 's/^data://p' | grep -E '\[(score|band|recruiter|decide)/'; done)
expect "recruiter step saw scores" grep -q "score=91" <<<"$SEEN"
expect "no gender, age, nationality, name or email after the filter" bash -c "! grep -qE 'gender|nationality|age=|email|Asha|Ben Carter|Chen Wei|Fatima' <<<\"\$SEEN\""

echo "--> batch without applicants is refused"
echo '{"role": "Backend engineer"}' > "$OUT/bad.json"
RUN=$(start_run "$OUT/bad.json")
finish "$RUN"
expect "refused" file_has summary.txt "BATCH REFUSED"

rm -rf "$OUT"
if (( FAILED )); then echo "FAIL 14-recruiting"; exit 1; fi
echo "PASS 14-recruiting: batch of 6 (invite, pool, decline, auto-decline, invalid) and a refused batch"
