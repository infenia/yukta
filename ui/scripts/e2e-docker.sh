#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Runs the mocked Playwright specs inside the same image CI uses, so screenshots match CI byte for byte.
# Screenshot baselines must only ever be written by this script (or CI), never by a bare `playwright test`:
#   scripts/e2e-docker.sh --update-snapshots            # regenerate every baseline
#   scripts/e2e-docker.sh e2e/tests/control-visual.spec.ts
# The image tag follows the installed @playwright/test, which is also what CI pins (checked in ci.yml).
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(node -p "require('@playwright/test/package.json').version")
image="mcr.microsoft.com/playwright:v${version}-noble"

# A worktree links node_modules to the main checkout; mount the real directory at its own path so the link resolves.
nm=$(realpath node_modules)

# Vite runs from node_modules (no pnpm in the image); --user keeps written files owned by you, not root.
exec docker run --rm --ipc=host --init \
  --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -e E2E_SERVER_CMD='node_modules/.bin/vite --host' \
  -v "$PWD":/work -v "$nm":"$nm" -w /work \
  "$image" node_modules/.bin/playwright test --project=chromium "$@"
