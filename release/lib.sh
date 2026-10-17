#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Shared helpers for the release scripts. Source it; do not run it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_DIR="$REPO_ROOT/release"
DIST="${DIST:-$REPO_ROOT/dist}"
IMAGE_GHCR="${IMAGE_GHCR:-ghcr.io/infenia/yukta}"
IMAGE_HUB="${IMAGE_HUB:-docker.io/infenia/yukta}"
# Pinned like the CLI's lint tools: run with `go run`, so nothing needs installing.
NFPM="github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.48.0"
PLATFORMS=(linux-amd64 linux-arm64)

die() { echo "release: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }

# 0.1.0, 0.1.0-rc.1 (a leading v is removed)
normalize_version() { echo "${1#v}"; }

gradle_version() { sed -n 's/^version=//p' "$REPO_ROOT/gradle.properties"; }

# Docker's name for the architecture of a platform: linux-arm64 -> arm64
arch_of() { echo "${1#*-}"; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed"; }

# A release that is not final (0.1.0-rc.1) must never move the moving tags.
is_prerelease() { [[ "$1" == *-* ]]; }
