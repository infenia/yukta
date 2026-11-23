#!/usr/bin/env bash
# Use case: check a project out and run it, the way a developer would. The workflow clones the todo
# app into a temp folder, starts its Python backend and its React frontend, and stays RUNNING while
# the app is up. The script waits until the page answers, adds a todo through the page's own /api
# proxy, then leaves the app up for you to try until you press Enter (or Ctrl-C); stopping the run
# stops both servers. With no terminal (CI), or HOLD=0, it stops at once.
# The workflow first checks that Python and bun (else node) are there; if not it tells the inbox and
# this script ends with SKIP (exit 3) and the message.
# Works on Linux, macOS and Windows (Git Bash); needs git, uv (it finds Python) and bun or node on this machine and a
# server that allows them (YUKTA_PROCESS_ALLOWED_COMMANDS=git,uv,bun,node,npm (npm.cmd on Windows) and
# YUKTA_VARIABLES_ALLOWED=java.io.tmpdir, which lets the workflow clone into the server's temp folder). Uses ports 8000 and 5173.
set -euo pipefail
source "$(dirname "$0")/../../lib/common.sh"
cd "$(dirname "$0")"
require_tools git
require_server
require_plugin PROCESS_EXECUTOR

SID=$(jq -r .sessionId session.json)
APP=http://127.0.0.1:5173
EID=""
cleanup() {
  [[ -z "$EID" ]] || api POST "/api/executions/$EID/stop?mode=KILL" >/dev/null 2>&1 || true
  sleep 2
}
trap cleanup EXIT

apply_session session.json
echo "--> start run (it clones into the temp folder; the first run downloads the packages)"
EID=$(api POST /api/workflow/start "$(jq -n --arg s "$SID" '{sessionId:$s,workflowId:"run"}')" | jq -r .data.executionId)

# up: the page, and the backend behind its proxy
up=false
for ((i = 0; i < ${WAIT_SECONDS:-180}; i++)); do
  curl -sf -m 3 "$APP/api/todos" >/dev/null 2>&1 && { up=true; break; }
  skip_on_notice "$EID"
  STATUS=$(api GET "/api/executions/$EID" 2>/dev/null | jq -r .data.status || true)
  [[ $STATUS =~ $TERMINAL_STATUSES ]] && break
  sleep 1
done
if [[ $up != true ]]; then
  LOGS=$(curl -s -m 15 ${YUKTA_API_KEY:+-H "X-API-Key: $YUKTA_API_KEY"} "$YUKTA_URL/api/sessions/$SID/executions/$EID/logs" | sed -n 's/^data://p' || true)
  skip_if_refused "$LOGS"
  skip_on_notice "$EID"
  echo "    FAIL: the app did not come up (status=${STATUS:-RUNNING})"; sed 's/^/      /' <<<"$LOGS" | tail -30; exit 1
fi

curl -sf -m 5 "$APP/" | grep -q '<div id="root">' || { echo "    FAIL: $APP did not serve the page"; exit 1; }
curl -sf -m 5 -X POST "$APP/api/todos" -H 'Content-Type: application/json' -d '{"title":"try Yukta"}' >/dev/null
curl -sf -m 5 "$APP/api/todos" | jq -e 'map(.title) | index("try Yukta")' >/dev/null || { echo "    FAIL: the todo did not come back"; exit 1; }
echo "    PASS: the page and the backend behind it answer ($APP)"

if [[ -t 0 && "${HOLD:-1}" != 0 ]]; then
  echo
  echo "    Open $APP and try it. Press Enter to stop the run (and both servers)."
  read -r _ || true
fi
