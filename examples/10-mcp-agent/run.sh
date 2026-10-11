#!/usr/bin/env bash
# Use case: an AI agent drives Yukta through MCP tools. This script makes the same
# JSON-RPC tools/call requests an MCP client would, using the hello-world session.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
MCP_URL="${YUKTA_URL}/mcp"
MCP_HEADERS=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
[[ -n "${YUKTA_API_KEY:-}" ]] && MCP_HEADERS+=(-H "Authorization: Bearer $YUKTA_API_KEY")

# mcp_call TOOL ARGUMENTS_JSON -> prints the tool's structured result
mcp_call() {
  local req resp
  req=$(jq -n --arg name "$1" --argjson args "$2" \
    '{jsonrpc: "2.0", id: 1, method: "tools/call", params: {name: $name, arguments: $args}}')
  resp=$(curl -sS -m 30 -X POST "$MCP_URL" "${MCP_HEADERS[@]}" -d "$req")
  if [[ $(jq -r '.result.isError // false' <<<"$resp") == "true" ]]; then
    echo "MCP tool $1 failed: $(jq -r '.result.content[0].text' <<<"$resp")" >&2
    return 1
  fi
  jq -c '.result.structuredContent' <<<"$resp"
}

require_server
session=$(jq -c . ../01-hello-world/session.json)
sid=$(jq -r .sessionId <<<"$session")

echo "--> create_session $sid"
mcp_call create_session "$(jq -n --argjson c "$session" '{config: $c}')" >/dev/null

echo "--> start_workflow hello"
eid=$(mcp_call start_workflow "$(jq -n --arg s "$sid" '{sessionId: $s, workflowId: "hello"}')" | jq -r .executionId)
echo "    executionId=$eid"

# Wait for the run over REST: MCP output validation rejects in-progress executions
# (null endTime/controlState), so the MCP read happens only once the run is terminal.
status=""
for ((i = 0; i < 60; i++)); do
  status=$(api GET "/api/executions/$eid" | jq -r .data.status)
  [[ $status =~ $TERMINAL_STATUSES ]] && break
  sleep 1
done
status=$(mcp_call get_workflow_history "$(jq -n --arg s "$sid" '{sessionId: $s}')" \
  | jq -r --arg e "$eid" '.executions[] | select(.executionId == $e) | .status')
echo "    status=$status"
[[ "$status" == "SUCCESS" ]] && echo "PASS mcp-agent: status=SUCCESS" || { echo "FAIL mcp-agent: status=$status"; exit 1; }
