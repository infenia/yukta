#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Build the native server (Liberica NIK) for the architecture of THIS machine, inside the same builder
# image the Dockerfile uses, so the result does not depend on what is installed locally.
# native-image cannot cross-compile: run it on an amd64 host for linux-amd64 and on an
# arm64 host (for example a GitHub-hosted ubuntu-24.04-arm runner) for linux-arm64.
#
# Builds with Liberica NIK, which is GraalVM Community Edition based (default -O2, no PGO).
# Usage: [NATIVE_HEAP=16g] [NATIVE_QUICK=1] release/build-server.sh
# NATIVE_QUICK=1 compiles with -Ob: much faster, for rehearsals and smoke tests. release.sh refuses
# to publish such a binary.
# Result: dist/linux-<arch>/yukta, yukta.sbom.json, BUILD (version, quick flag, commit) and SHA256SUMS
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
# Peak memory of the whole machine during the build (the build runs in Docker, so a per-process
# figure is not available): sample MemAvailable and swap every 5 s, print the extremes at the end.
peak_log="$(mktemp)"
(while sleep 5; do awk '/MemTotal/{t=$2} /MemAvailable/{a=$2} /SwapTotal/{st=$2} /SwapFree/{sf=$2} END{print int((t-a)/1024), int((st-sf)/1024)}' /proc/meminfo; done >"$peak_log") &
sampler=$!
docker build --target builder --build-arg "NATIVE_HEAP=$heap" \
  --build-arg "NATIVE_QUICK=${NATIVE_QUICK:-}" \
  -t "yukta-builder:$platform" "$REPO_ROOT"
kill "$sampler" 2>/dev/null || true
sort -n "$peak_log" | tail -1 | awk '{print "peak machine memory used: " $1 " MB"}'
sort -k2 -n "$peak_log" | tail -1 | awk '{print "peak swap used: " $2 " MB"}'
rm -f "$peak_log"
cid="$(docker create "yukta-builder:$platform")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT
docker cp "$cid:/build/boot/build/native/nativeCompile/yukta" "$out/yukta"
docker cp "$cid:/build/boot/build/native/nativeCompile/yukta.sbom.json" "$out/yukta.sbom.json"
chmod 0755 "$out/yukta"
write_build_marker "$out"
(cd "$out" && sha256sum yukta > SHA256SUMS)
log "server binary: $out/yukta"
