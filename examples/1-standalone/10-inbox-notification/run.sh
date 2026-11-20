#!/usr/bin/env bash
# Use case: a workflow raises a notification in the Yukta inbox, and the inbox shows it.
set -euo pipefail
source "$(dirname "$0")/../../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . report SUCCESS "nightly report ready"
echo "--> the inbox"
api GET "/api/notifications?kind=INFO" | jq -e '.data[] | select(.title == "Nightly report ready" and .category == "workflow")' >/dev/null \
  && echo "    found: Nightly report ready"
