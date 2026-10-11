#!/usr/bin/env bash
# Use case: wait for a dependency (service, build artifact, lock) to become ready.
# LOOP_PREDICATE repeats the target node until exitCondition holds or limits are hit.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . wait-ready SUCCESS "checking readiness"
