#!/usr/bin/env bash
# Run the yukta CLI against the dev server (YUKTA_SERVER_URL, else .yukta-dev/current.env), so
# callers need neither PATH nor URL set up. Example:
#   yukta-cli.sh workflow node retry <execution-id> <node-id>
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

load_server
exec "$(find_cli)" "$@"
