#!/usr/bin/env bash
# Use case: two scripted agents in one run, and their conversations in one place.
# Needs a server with AGENT_SESSION on, `sh` allowed, and the preset folder of this example (see the README);
# exit 3 (SKIP) otherwise.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
require_plugin AGENT_SESSION
if ! api GET /api/plugins/AGENT_SESSION | jq -e '[.. | strings] | any(test("fake-agent"))' >/dev/null; then
  echo "SKIP: the harness 'fake-agent' is not on this server (set yukta.ai.presets-dir to $(pwd)/agent-harnesses)"
  exit 3
fi
apply_session session.json
run_workflow . chain SUCCESS "reading the review"

echo "--> the conversations of the run"
count=$(api GET "/api/conversations?sessionId=example-21-agent-console" | jq '.data | length')
echo "    conversations of the session: $count"
[[ "$count" -ge 2 ]] || { echo "    FAIL: expected a conversation for each agent"; exit 1; }
echo "    PASS: a conversation for each agent"
