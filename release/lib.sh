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

# Records what a build directory holds, so release.sh can refuse a leftover or rehearsal binary.
# The directory travels with the CI artifact, so the marker is checked on the release machine too.
write_build_marker() {
  printf 'version=%s\nquick=%s\ncommit=%s\n' "$(gradle_version)" "${NATIVE_QUICK:+1}" \
    "$(git -C "$REPO_ROOT" rev-parse HEAD)" > "$1/BUILD"
}

# check_build_marker <dir> <version> <publish>: the build must be of <version>, not a NATIVE_QUICK
# (-Ob) rehearsal and, for a publish, of the checked-out commit.
check_build_marker() {
  local dir="$1" version="$2" publish="$3" marker="$1/BUILD"
  [[ -f "$marker" ]] || die "$dir has no BUILD marker: rebuild it, or download the CI artifact again"
  [[ "$(sed -n 's/^version=//p' "$marker")" == "$version" ]] ||
    die "$dir was built for version '$(sed -n 's/^version=//p' "$marker")', not '$version'"
  [[ -z "$(sed -n 's/^quick=//p' "$marker")" ]] || die "$dir holds a NATIVE_QUICK (-Ob) rehearsal build"
  if [[ "$publish" == true ]]; then
    [[ "$(sed -n 's/^commit=//p' "$marker")" == "$(git -C "$REPO_ROOT" rev-parse HEAD)" ]] ||
      die "$dir was built from another commit than HEAD"
  fi
}
