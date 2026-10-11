#!/usr/bin/env bash
# Use case: a batch ETL. Records are split into messages, inactive ones are dropped,
# the rest are reshaped. The terminal output shows only the Ada and Linus messages.
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . etl SUCCESS "user:Linus"
