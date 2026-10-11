#!/usr/bin/env bash
# Use case: an external system (script, CI job, another service) starts a workflow
# over REST and passes its own data in the request body.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . greet-user SUCCESS "Hello, Ada" payload.json
