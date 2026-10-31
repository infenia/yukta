#!/usr/bin/env bash
# Shared helpers for the Yukta examples. Source this file; do not run it directly.
#
# Environment:
#   YUKTA_URL      server base URL (default http://localhost:9858)
#   YUKTA_API_KEY  optional; sent as X-API-Key when set
#   WAIT_SECONDS   how long run_workflow waits for a terminal status (default 60)

YUKTA_URL="${YUKTA_URL:-http://localhost:9858}"
TERMINAL_STATUSES='^(SUCCESS|FAILURE|ERROR|STOPPED|CANCELLED|TIMED_OUT)$'

# api METHOD PATH [JSON] -> prints the response body; fails on HTTP >= 400.
api() {
  local out code body
  local args=(-sS -m 60 -X "$1" "$YUKTA_URL$2" -H 'Content-Type: application/json' -w $'\n%{http_code}')
  [[ -n "${YUKTA_API_KEY:-}" ]] && args+=(-H "X-API-Key: $YUKTA_API_KEY")
  [[ $# -ge 3 ]] && args+=(--data-binary "$3")
  out=$(curl "${args[@]}") || { echo "cannot reach $YUKTA_URL (is the server running?)" >&2; return 1; }
  code=${out##*$'\n'}
  body=${out%$'\n'*}
  if (( code >= 400 )); then
    echo "HTTP $code from $1 $2: $body" >&2
    return 1
  fi
  printf '%s' "$body"
}

# require_server: fails fast with a clear message when the server is down.
require_server() {
  api GET /api/plugins >/dev/null || exit 2
}

# require_plugin TYPE: exit 3 (SKIP) when the running server has no such plugin.
require_plugin() {
  if ! api GET "/api/plugins/$1" >/dev/null 2>&1; then
    echo "SKIP: plugin '$1' is not registered on this server (build with it enabled)"
    exit 3
  fi
}

# apply_session FILE: registers (or replaces) the session described by FILE.
apply_session() {
  api POST /api/sessions "$(cat "$1")" >/dev/null
  echo "applied session $(jq -r .sessionId "$1") from $(basename "$(dirname "$1")")/$(basename "$1")"
}

# run_workflow DIR WORKFLOW EXPECT_STATUS EXPECT_TEXT [PAYLOAD_FILE]
#   Applies DIR/session.json, starts WORKFLOW (with PAYLOAD_FILE if given), waits for
#   a terminal status, prints the logs, and fails unless the status equals EXPECT_STATUS
#   and the logs contain EXPECT_TEXT.
run_workflow() {
  local dir=$1 wf=$2 want_status=$3 want_text=$4 payload=${5:-}
  local sid body eid status logs i
  sid=$(jq -r .sessionId "$dir/session.json")
  body=$(jq -n --arg s "$sid" --arg w "$wf" '{sessionId: $s, workflowId: $w}')
  if [[ -n "$payload" ]]; then
    body=$(jq --argjson p "$(cat "$payload")" '. + {payload: $p}' <<<"$body")
  fi

  echo "--> start $wf"
  echo "    curl -X POST $YUKTA_URL/api/workflow/start -H 'Content-Type: application/json' -d '$(jq -c . <<<"$body")'"
  eid=$(api POST /api/workflow/start "$body" | jq -r .data.executionId)

  status=""
  for ((i = 0; i < ${WAIT_SECONDS:-60}; i++)); do
    # The execution can 404 for a moment right after start; keep polling.
    status=$(api GET "/api/executions/$eid" 2>/dev/null | jq -r .data.status || true)
    [[ $status =~ $TERMINAL_STATUSES ]] && break
    sleep 1
  done

  # The log stream closes itself once the execution is terminal; -m caps a stuck run.
  logs=$(curl -s -m 15 "$YUKTA_URL/api/sessions/$sid/executions/$eid/logs" | sed -n 's/^data://p' || true)
  echo "    logs:"
  sed 's/^/      /' <<<"$logs"

  if [[ "$status" == "$want_status" ]] && grep -qF -- "$want_text" <<<"$logs"; then
    echo "    PASS $wf: status=$status, found '$want_text'"
  else
    echo "    FAIL $wf: status=$status (want $want_status), '$want_text' in logs: $(grep -qF -- "$want_text" <<<"$logs" && echo yes || echo no)"
    return 1
  fi
}
