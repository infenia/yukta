#!/usr/bin/env bash
# Use case: one event must reach several channels. RECIPIENT_LIST routes a copy to each
# named port; all three notifications should appear in the logs.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . notify SUCCESS "notified: pager"
