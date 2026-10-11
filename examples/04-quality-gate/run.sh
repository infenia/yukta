#!/usr/bin/env bash
# Use case: an AI agent (or CI job) posts change metrics and gets a pass/fail decision.
# The BRANCH node evaluates SpEL predicates on the payload and picks the output port.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . gate SUCCESS "QUALITY GATE PASSED" pass.json
run_workflow . gate SUCCESS "QUALITY GATE FAILED" fail.json
