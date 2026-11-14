#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Drives the web UI of a running server, in a browser: creates a session from every bundled template and
# runs examples (with a human answer in the UI for one of them). It is the UI half of examples-smoke.sh:
# ui/e2e/real/examples.spec.ts, run by Playwright against the real server (no mocks), so it can be
# repeated on the native binary, the Docker image or a bootRun server.
#
# Usage: release/ui-smoke.sh <base-url>        e.g. release/ui-smoke.sh http://127.0.0.1:9858
# Needs: the UI's dependencies (cd ui && pnpm install) and a Chromium for Playwright
#        (cd ui && pnpm exec playwright install chromium), and a server started as the examples need it:
#        YUKTA_PLUGINS_ENABLED=all YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo,python3 (examples/README.md)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 ]] || die "usage: ui-smoke.sh <base-url>"
need pnpm
[[ -d "$REPO_ROOT/ui/node_modules/@playwright/test" ]] || die "ui smoke: run 'pnpm install' in ui/ first"

log "driving the UI of ${1%/}"
cd "$REPO_ROOT/ui"
YUKTA_URL="${1%/}" pnpm exec playwright test -c playwright.real.config.ts --project=chromium
log "ui smoke passed"
