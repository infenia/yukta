#!/usr/bin/env bash
# Use case: a CI pipeline as a workflow. It clones the todo app into a temp folder, checks the Python
# backend (lint, format, test) and then the React frontend (install, lint, format, test); the first
# failing stage stops the run. Works on Linux, macOS and Windows (Git Bash); needs git, uv (it finds Python)
# and bun or node on this machine and a server that allows them (YUKTA_PROCESS_ALLOWED_COMMANDS=git,uv,bun,node,npm; npm.cmd on Windows).
# A preflight stage checks that Python and bun (else node) are there; if not it tells the inbox and this
# script ends with SKIP (exit 3) and the message.
set -euo pipefail
source "$(dirname "$0")/../../lib/common.sh"
cd "$(dirname "$0")"
require_tools git
require_server
require_plugin PROCESS_EXECUTOR

SID=$(jq -r .sessionId session.json)
DIR=$(temp_dir yukta-todo-ci)
trap 'rm -rf "$DIR"' EXIT

apply_session session.json
payload=$(jq --arg dir "$DIR/todo" --arg npm "$(npm_command)" '. + {dir: $dir, npm: $npm}' payload.json)
echo "--> start ci in $DIR (the first run downloads the tools' packages)"
EID=$(api POST /api/workflow/start "$(jq -n --arg s "$SID" --argjson p "$payload" '{sessionId:$s,workflowId:"ci",payload:$p}')" | jq -r .data.executionId)

STATUS=""
for ((i = 0; i < ${WAIT_SECONDS:-300}; i++)); do
  STATUS=$(api GET "/api/executions/$EID" 2>/dev/null | jq -r .data.status || true)
  [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
  sleep 1
done
LOGS=$(curl -s -m 15 ${YUKTA_API_KEY:+-H "X-API-Key: $YUKTA_API_KEY"} "$YUKTA_URL/api/sessions/$SID/executions/$EID/logs" | sed -n 's/^data://p' || true)

skip_on_notice "$EID"   # a missing tool ends the run in a notification, which is not a pass
echo "    stages:"
api GET "/api/executions/$EID" | jq -r '.data.tasks[] | select(.nodeId != "trigger") | "      \(.status)\t\(.nodeId)"'
if [[ $STATUS == SUCCESS ]]; then
  echo "    PASS ci: every stage passed"
else
  skip_if_refused "$LOGS"
  echo "    FAIL ci: status=$STATUS"; sed 's/^/      /' <<<"$LOGS" | tail -30; exit 1
fi
