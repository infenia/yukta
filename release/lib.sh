#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Shared helpers for the release scripts. Source it; do not run it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_DIR="$REPO_ROOT/release"
DIST="${DIST:-$REPO_ROOT/dist}"
# Registries to publish to, space separated. The first one is the one built and smoke tested locally.
# Docker Hub returns by setting YUKTA_IMAGES="ghcr.io/infenia/yukta docker.io/infenia/yukta".
read -r -a IMAGES <<< "${YUKTA_IMAGES:-ghcr.io/infenia/yukta}"
IMAGE_GHCR="${IMAGES[0]}"
# Pinned like the CLI's lint tools: run with `go run`, so nothing needs installing.
NFPM="github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.48.0"
# The alpha ships linux-amd64 only; add linux-arm64 back when its runner build is proven.
PLATFORMS=(linux-amd64)

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
