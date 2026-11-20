#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# One command that starts the server as a boot jar, the native binary or the Docker image, runs every
# example against it (release/examples-smoke.sh) and drives the web UI (release/ui-smoke.sh: the examples
# are offered as templates, a session is created from each, and examples run from the UI), then stops
# the server. Repeat it any time.
#
# Usage: release/examples-verify.sh jar|native|docker [--no-ui]
#   jar     boot/build/libs/boot-*.jar   (build it first: yk gradle :boot:bootJar)
#   native  dist/linux-<arch>/yukta       (release/build-server.sh; NATIVE_QUICK=1 for a fast one)
#   docker  $YUKTA_IMAGE, default ghcr.io/infenia/yukta:0.0.0-local-amd64 (release/build-image.sh)
#   --no-ui skip the browser half (it needs pnpm install and a Playwright Chromium in ui/)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

mode="${1:-}"
ui=true
[[ "${2:-}" != --no-ui ]] || ui=false
[[ "$mode" =~ ^(jar|native|docker)$ ]] || die "usage: examples-verify.sh jar|native|docker [--no-ui]"
need curl
need jq
need python3

port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
key="verify-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
url="http://127.0.0.1:$port"
work="$(mktemp -d)"
pid=""
container=""

cleanup() {
  [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true
  [[ -z "$container" ]] || docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# What the examples need (examples/README.md): every plugin, the commands they run, an ADMIN key.
server_env=(
  YUKTA_PLUGINS_ENABLED=all
  YUKTA_API_KEYS_0_NAME=verify YUKTA_API_KEYS_0_KEY="$key" YUKTA_API_KEYS_0_ROLE=ADMIN "YUKTA_API_KEYS_0_SESSIONS_0=*"
  YUKTA_AI_PRESETS_DIR="$REPO_ROOT/examples/21-agent-console/agent-harnesses"
)
# The UI tests send more requests a minute than one key may.
rate=-Dyukta.rate-limit-per-minute=100000

case "$mode" in
  jar)
    jar="$(ls "$REPO_ROOT"/boot/build/libs/boot-*.jar 2>/dev/null | grep -v -- '-plain.jar' | tail -1 || true)"
    [[ -n "$jar" ]] || die "no boot jar: run 'yk gradle :boot:bootJar'"
    need java
    log "jar: $jar"
    env "${server_env[@]}" SERVER_PORT="$port" YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo,python3 \
      JAVA_TOOL_OPTIONS="$rate" java -jar "$jar" >"$work/server.log" 2>&1 &
    pid=$!
    ;;
  native)
    bin="$DIST/linux-$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')/yukta"
    [[ -x "$bin" ]] || die "no native binary at $bin: run release/build-server.sh"
    log "native: $bin"
    env "${server_env[@]}" SERVER_PORT="$port" YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo,python3 \
      "$bin" "$rate" >"$work/server.log" 2>&1 &
    pid=$!
    ;;
  docker)
    need docker
    image="${YUKTA_IMAGE:-ghcr.io/infenia/yukta:0.0.0-local-amd64}"
    log "docker: $image"
    # The examples keep files in /tmp/yukta-examples; the container shares that folder with this machine.
    mkdir -p /tmp/yukta-examples
    docker_env=()
    for e in "${server_env[@]}"; do [[ "$e" == YUKTA_AI_PRESETS_DIR=* ]] || docker_env+=(-e "$e"); done
    container="$(docker run -d --rm -p "127.0.0.1:$port:9858" --user "$(id -u):$(id -g)" --workdir /tmp -e HOME=/tmp \
      -v /tmp/yukta-examples:/tmp/yukta-examples "${docker_env[@]}" \
      -e YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo -e "JAVA_TOOL_OPTIONS=$rate" "$image")"
    export SMOKE_IN_CONTAINER=1
    ;;
esac

log "waiting for $url"
for ((i = 0; i < 90; i++)); do
  curl -fsS "$url/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && break
  [[ -z "$pid" ]] || kill -0 "$pid" 2>/dev/null || { tail -40 "$work/server.log" >&2; die "the server exited"; }
  sleep 2
done
curl -fsS "$url/actuator/health" | grep -q '"status":"UP"' || die "the server never reported UP"

# Both halves run even when the first fails, so one run reports everything that is broken.
# Every example, also the four the e2e suite runs in the JVM: this checks the binary, not the code.
failed=()
set +e
EXAMPLES_SKIP="${EXAMPLES_SKIP:- }" "$RELEASE_DIR/examples-smoke.sh" "$url" "$key" >"$work/examples.out" 2>&1
[[ $? -eq 0 ]] || failed+=(examples)
if $ui; then
  YUKTA_API_KEY="$key" "$RELEASE_DIR/ui-smoke.sh" "$url" >"$work/ui.out" 2>&1
  [[ $? -eq 0 ]] || failed+=(ui)
fi
set -e
cat "$work/examples.out"
if $ui; then tail -n 20 "$work/ui.out"; fi

echo
echo "=================== verify report ($mode)"
if [[ ${#failed[@]} -eq 0 ]]; then
  log "examples verified on $mode"
  exit 0
fi
for half in "${failed[@]}"; do
  echo "FAILED: $half on $mode. The error:"
  if [[ $half == examples ]]; then
    grep -E '^(FAIL |    FAIL|examples smoke:)' "$work/examples.out" | sed 's/^/  /'
  else
    # Playwright's failed tests and their first error lines
    grep -E '✘|^\s+(Error|TypeError|Expected|Received)|error:|[0-9]+ failed' "$work/ui.out" | head -30 | sed 's/^/  /'
  fi
done
echo "Server log, errors and warnings (last 15):"
if [[ -n "$container" ]]; then docker logs "$container" 2>&1; else cat "$work/server.log"; fi | grep -E " (ERROR|WARN) " | tail -n 15 | sed 's/^/  /' || true
mkdir -p "$REPO_ROOT/build"
cp "$work/examples.out" "$REPO_ROOT/build/examples-verify.examples.log"
[[ ! -f "$work/ui.out" ]] || cp "$work/ui.out" "$REPO_ROOT/build/examples-verify.ui.log"
log "full output: build/examples-verify.examples.log, build/examples-verify.ui.log"
exit 1
