#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Build the native server (GraalVM) for the architecture of THIS machine, inside the same pinned
# builder image the Dockerfile uses, so the result does not depend on what is installed locally.
# GraalVM native-image cannot cross-compile: run it on an amd64 host for linux-amd64 and on an
# arm64 host (for example a GitHub-hosted ubuntu-24.04-arm runner) for linux-arm64.
#
# Builds with Oracle GraalVM at -O3 and a PGO profile (boot/pgo/yukta.iprof, never committed). A full
# build records a fresh profile first (release/pgo-profile.sh: instrumented build + examples), so the
# profile always matches this commit and architecture. If recording fails the build goes on without
# one and Oracle GraalVM infers a profile itself (GraalNN). NATIVE_PGO=0 skips recording.
# NATIVE_PGO_INSTRUMENT=1 builds the instrumented binary instead (what pgo-profile.sh uses).
# NATIVE_PGO_REQUIRED=1 fails the build when no profile can be recorded (the release workflow sets it).
# NATIVE_PGO_PROFILE=<file[.gz]> builds with that recorded profile instead of recording a new one, to
# rebuild a release from the pgo-profile asset attached to it.
# Usage: [NATIVE_HEAP=16g] [NATIVE_QUICK=1] [NATIVE_PGO_INSTRUMENT=1] release/build-server.sh
# NATIVE_QUICK=1 compiles with -Ob: much faster, for rehearsals and smoke tests. release.sh refuses
# to publish such a binary.
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

profile="$REPO_ROOT/boot/pgo/yukta.iprof"
if [[ -n "${NATIVE_PGO_PROFILE:-}" ]]; then
  [[ -s "$NATIVE_PGO_PROFILE" ]] || die "NATIVE_PGO_PROFILE=$NATIVE_PGO_PROFILE is not a file"
  mkdir -p "$(dirname "$profile")"
  case "$NATIVE_PGO_PROFILE" in
    *.gz) gunzip -c "$NATIVE_PGO_PROFILE" > "$profile" ;;
    *) cp "$NATIVE_PGO_PROFILE" "$profile" ;;
  esac
elif [[ -z "${NATIVE_QUICK:-}" && -z "${NATIVE_PGO_INSTRUMENT:-}" && "${NATIVE_PGO:-1}" != 0 ]]; then
  rm -f "$profile"
  if ! "$RELEASE_DIR/pgo-profile.sh"; then
    [[ -z "${NATIVE_PGO_REQUIRED:-}" ]] || die "no PGO profile recorded and NATIVE_PGO_REQUIRED is set"
    log "WARNING: no PGO profile recorded; building without one (GraalNN)"
  fi
elif [[ -z "${NATIVE_PGO_INSTRUMENT:-}" ]]; then
  rm -f "$profile"   # quick or NATIVE_PGO=0 builds must not pick up a stale profile
fi

log "native compile for $platform (builder heap $heap)"
docker build --target builder --build-arg "NATIVE_HEAP=$heap" \
  --build-arg "NATIVE_QUICK=${NATIVE_QUICK:-}" \
  --build-arg "NATIVE_PGO_INSTRUMENT=${NATIVE_PGO_INSTRUMENT:-}" \
  -t "yukta-builder:$platform" "$REPO_ROOT"
cid="$(docker create "yukta-builder:$platform")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT
docker cp "$cid:/build/boot/build/native/nativeCompile/yukta" "$out/yukta"
chmod 0755 "$out/yukta"
# Kept next to the binary so the release can attach the profile this binary was built with.
if [[ -z "${NATIVE_PGO_INSTRUMENT:-}" && -s "$profile" ]]; then cp "$profile" "$out/yukta.iprof"; else rm -f "$out/yukta.iprof"; fi
(cd "$out" && sha256sum yukta > SHA256SUMS)
log "server binary: $out/yukta"
