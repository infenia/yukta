#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Record the PGO profile the native build uses (boot/pgo/yukta.iprof): build an instrumented
# server, run the examples against it, and stop it so it writes the profile. Run it before a
# release (release/build-server.sh does this itself); the profile is not committed.
#
# Usage: [ROUNDS=5] [NATIVE_HEAP=16g] release/pgo-profile.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

need curl
case "$(uname -m)" in
  x86_64 | amd64) platform=linux-amd64 ;;
  aarch64 | arm64) platform=linux-arm64 ;;
  *) die "unsupported machine $(uname -m)" ;;
esac
bin="$DIST/$platform/yukta"
profile="$REPO_ROOT/boot/pgo/yukta.iprof"

log "building the instrumented server"
NATIVE_PGO_INSTRUMENT=1 "$REPO_ROOT/release/build-server.sh"

work="$(mktemp -d)"
key="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
pid=""
cleanup() { [[ -z $pid ]] || kill "$pid" 2>/dev/null || true; rm -rf "$work"; rm -f "$bin" "$DIST/$platform/SHA256SUMS"; }
trap cleanup EXIT

# The instrumented binary writes default.iprof into its working directory when it exits.
log "starting it and training with the examples"
(cd "$work" && YUKTA_API_KEY="$key" exec "$bin") >"$work/server.log" 2>&1 &
pid=$!
for _ in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:8080/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && break
  kill -0 "$pid" 2>/dev/null || die "server exited early: $(tail -5 "$work/server.log")"
  sleep 2
done

for i in $(seq 1 "${ROUNDS:-5}"); do
  log "training round $i"
  YUKTA_API_KEY="$key" "$REPO_ROOT/examples/run-all.sh" >>"$work/examples.log" 2>&1 || true
done

kill -TERM "$pid"
wait "$pid" || true
pid=""
[[ -s $work/default.iprof ]] || die "no profile written; see $work/server.log"
mkdir -p "$(dirname "$profile")"
cp "$work/default.iprof" "$profile"
log "profile: $profile ($(du -h "$profile" | cut -f1))."
