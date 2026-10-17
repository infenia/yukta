#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Build the native server (GraalVM) for the architecture of THIS machine, inside the same pinned
# builder image the Dockerfile uses, so the result does not depend on what is installed locally.
# GraalVM native-image cannot cross-compile: run it on an amd64 host for linux-amd64 and on an
# arm64 host (for example a GitHub-hosted ubuntu-24.04-arm runner) for linux-arm64.
#
# Usage: [NATIVE_HEAP=16g] release/build-server.sh
# Result: dist/linux-<arch>/yukta and dist/linux-<arch>/SHA256SUMS
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

need docker
case "$(uname -m)" in
  x86_64 | amd64) platform=linux-amd64 ;;
  aarch64 | arm64) platform=linux-arm64 ;;
  *) die "unsupported machine $(uname -m)" ;;
esac
heap="${NATIVE_HEAP:-16g}"
out="$DIST/$platform"
mkdir -p "$out"

log "native compile for $platform (builder heap $heap)"
docker build --target builder --build-arg "NATIVE_HEAP=$heap" \
  -t "yukta-builder:$platform" "$REPO_ROOT"
cid="$(docker create "yukta-builder:$platform")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT
docker cp "$cid:/build/boot/build/native/nativeCompile/yukta" "$out/yukta"
chmod 0755 "$out/yukta"
(cd "$out" && sha256sum yukta > SHA256SUMS)
log "server binary: $out/yukta"
