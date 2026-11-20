#!/usr/bin/env bash
# Use case: the first workflow to read and copy: input with a default, a mapper, a branch, a printed result.
# Needs only the server (no plugin allowlist). Run it with no payload, then an odd and an even text.
set -euo pipefail
source "$(dirname "$0")/../../lib/common.sh"
cd "$(dirname "$0")"
require_server
apply_session session.json
run_workflow . hello SUCCESS "Hello, Yukta! Digit 5 is odd."
run_workflow . hello SUCCESS "Hello, Grace! Digit 5 is odd." odd.json
run_workflow . hello SUCCESS "Hello, Lovelace! Digit 8 is even." even.json
