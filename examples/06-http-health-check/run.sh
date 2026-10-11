#!/usr/bin/env bash
# Use case: a monitoring or smoke check. HTTP_REQUEST routes to "success" for a 2xx
# answer and to "failure" otherwise, so the alert path is part of the workflow.
# Needs the http-request plugin on the server (the default build includes it).
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
require_plugin HTTP_REQUEST

# The OK probe targets this server, so point it at YUKTA_URL when that differs.
work=$(mktemp -d)
jq --arg url "$YUKTA_URL/api/plugins" '
  (.workflows["health-ok"].nodes[] | select(.nodeId == "probe").config.url) = $url
' session.json > "$work/session.json"

apply_session "$work/session.json"
run_workflow "$work" health-ok SUCCESS "HEALTH OK"
run_workflow "$work" health-down SUCCESS "HEALTH ALERT"
