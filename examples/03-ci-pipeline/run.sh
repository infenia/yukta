#!/usr/bin/env bash
# Use case: a CI gate. A passing check takes the success port, a failing check takes
# the failure port. Both runs are asserted on the report that was printed.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . ci-pass SUCCESS "PIPELINE PASSED"
run_workflow . ci-fail SUCCESS "PIPELINE FAILED"
