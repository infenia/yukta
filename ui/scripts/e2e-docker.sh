#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Runs the mocked Playwright specs inside the image CI uses, to reproduce CI's browser and fonts locally.
# It is NOT guaranteed to render pixel-identical to the GitHub runner (a baseline written here was rejected by CI),
# so CI stays the authority for baselines: when CI fails on a screenshot, take the expected/actual images from its
# `playwright-report` artifact (`gh run download <run> -n playwright-report`).
#   scripts/e2e-docker.sh                               # run the specs
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
