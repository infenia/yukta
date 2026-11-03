#!/usr/bin/env bash
# A pull responder: list the input requests that wait on the server, then answer one.
# It needs an OPERATOR key (an agent that only reads can use a VIEWER key for `list`).
#
#   inputs.sh list                                  open requests of all sessions
#   inputs.sh answer EXECUTION REQUEST DECISION [TEXT]
#
# Environment: YUKTA_URL (default http://localhost:9858), YUKTA_API_KEY (sent as X-API-Key).
set -euo pipefail

YUKTA_URL="${YUKTA_URL:-http://localhost:9858}"
auth=()
[[ -n "${YUKTA_API_KEY:-}" ]] && auth=(-H "X-API-Key: ${YUKTA_API_KEY}")

call() { curl -fsS "${auth[@]+"${auth[@]}"}" "$@"; }

case "${1:-list}" in
  list)
    call "${YUKTA_URL}/api/inputs?limit=${LIMIT:-50}" |
      jq -r '.data[] | [.id, .executionId, .risk, .tier, (.prompt | .[0:60])] | @tsv'
    ;;
  answer)
    [[ $# -ge 4 ]] || { echo "usage: $0 answer EXECUTION REQUEST DECISION [TEXT]" >&2; exit 2; }
    body=$(jq -nc --arg d "$4" --arg t "${5:-}" \
      '{decision: $d} + (if $t == "" then {} else {text: $t} end)')
    call -X POST -H 'Content-Type: application/json' -d "${body}" \
      "${YUKTA_URL}/api/executions/$2/inputs/$3/answer" | jq -r '.message'
    ;;
  *)
    echo "usage: $0 list | answer EXECUTION REQUEST DECISION [TEXT]" >&2
    exit 2
    ;;
esac
