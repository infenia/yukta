#!/usr/bin/env bash
# Use case: run the smallest workflow end to end.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . hello SUCCESS "Hello from Yukta!"
