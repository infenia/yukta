#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Build the Go CLI (yukta-cli) for all five platforms into dist/cli/ and write SHA256SUMS.
# Usage: release/build-cli.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

need make
need go
out="$DIST/cli"
rm -rf "$out"
mkdir -p "$out"
log "building yukta-cli for all platforms"
make -C "$REPO_ROOT/cli" build-all VERSION="$(gradle_version)"
cp "$REPO_ROOT"/cli/build/yukta-cli-* "$out/"
write_build_marker "$out"
(cd "$out" && sha256sum yukta-cli-* > SHA256SUMS)
log "CLI binaries in $out"
