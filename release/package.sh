#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Package one platform: a .tar.gz, plus a .deb and an .rpm for Linux. Needs the server binary
# (release/build-server.sh, or one downloaded from CI) and the CLI binaries (release/build-cli.sh).
#
# Usage: release/package.sh <version> <platform>     e.g. 0.1.0-rc.1 linux-arm64
# Result: dist/packages/
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 2 ]] || die "usage: package.sh <version> <platform>"
version="$(normalize_version "$1")"
platform="$2"
arch="$(arch_of "$platform")"
server="$DIST/$platform/yukta"
cli="$DIST/cli/yukta-cli-$platform"
[[ -x "$server" ]] || die "missing $server (run release/build-server.sh on a $platform machine)"
[[ -x "$cli" ]] || die "missing $cli (run release/build-cli.sh)"
need go
out="$DIST/packages"
mkdir -p "$out"

name="yukta-$version-$platform"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/$name"
cp "$server" "$stage/$name/yukta"
cp "$cli" "$stage/$name/yukta-cli"
cp "$REPO_ROOT/LICENSE" "$REPO_ROOT/NOTICE" "$RELEASE_DIR/yukta.env" "$stage/$name/"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-01-01 00:00:00 UTC' \
  -C "$stage" -czf "$out/$name.tar.gz" "$name"
log "$out/$name.tar.gz"

for format in deb rpm; do
  (cd "$REPO_ROOT" && VERSION="$version" ARCH="$arch" SERVER_BIN="$server" CLI_BIN="$cli" \
    go run "$NFPM" package --config release/nfpm.yaml --packager "$format" --target "$out/")
done
# nfpm writes a prerelease as 0.1.0~alpha.1. GitHub stores a "~" in an asset name as ".", so the name
# in SHA256SUMS would no longer match the download. Use the stored form from the start.
for f in "$out"/*~*; do
  [[ -e "$f" ]] && mv "$f" "${f//\~/.}"
done
