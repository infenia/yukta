#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Starts and stops the server the non-JVM end-to-end tiers run against: the CLI tests (cli/e2e), the
# real-server Playwright tests and the examples. It is the boot jar on a free loopback port with an
# ADMIN key, every plugin and the process commands `sh` and `echo`.
#
# Usage: release/e2e-server.sh start|stop|url|key|viewer-key
#   start  build the jar if there is none, start it, wait for /actuator/health, write build/e2e-server/
#   stop   stop it; if it had already died, print the tail of its log
#   url    print the base URL of the running server
#   key    print the ADMIN API key
#   viewer-key  print the VIEWER API key (reads only; for the role tests)
# Env:   YUKTA_E2E_PORT        port to use (default: a free one)
#        YUKTA_E2E_KEY         ADMIN key to use (default: a random one)
#        YUKTA_E2E_VIEWER_KEY  VIEWER key to use (default: a random one)
#        YUKTA_E2E_ALLOW_ANY   true to let workflows run any executable (the examples need it)
#        JAVA_TOOL_OPTIONS     passed to the JVM (for example the JaCoCo agent)
#        JAVA                  the java binary (default: java)
# The jar is built with the Gradle wrapper when it is missing; in CI it is built first. Locally,
# build it through yk (`yk gradle :boot:bootJar`) before you start the server.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
dir="$root/build/e2e-server"
java_bin=${JAVA:-java}

# The boot jar, not the -plain one.
jar() {
  local candidate
  for candidate in "$root"/boot/build/libs/boot-*.jar; do
    case $candidate in
      *-plain.jar) ;;
      *)
        if [ -f "$candidate" ]; then
          echo "$candidate"
          return 0
        fi
        ;;
    esac
  done
  return 1
}

free_port() {
  python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

running() {
  [ -f "$dir/pid" ] && kill -0 "$(cat "$dir/pid")" 2>/dev/null
}

start() {
  if running; then
    echo "already running at $(cat "$dir/url")" >&2
    return 0
  fi
  local found
  if ! found=$(jar); then
    echo "no boot jar: building it" >&2
    (cd "$root" && ./gradlew :boot:bootJar -q --console=plain)
    found=$(jar) || {
      echo "the build made no boot jar" >&2
      return 1
    }
  fi
  mkdir -p "$dir"
  local port=${YUKTA_E2E_PORT:-$(free_port)}
  local key=${YUKTA_E2E_KEY:-e2e-$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')}
  local viewer=${YUKTA_E2E_VIEWER_KEY:-e2e-$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')}
  local process=YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo
  if [ "${YUKTA_E2E_ALLOW_ANY:-false}" = true ]; then
    process=YUKTA_PROCESS_ALLOW_ANY=true
  fi
  # A suite sends more than the 1200 requests a minute one key may; this is a throwaway server.
  local options="-Dyukta.rate-limit-per-minute=100000 ${JAVA_TOOL_OPTIONS:-}"
  : >"$dir/server.log"
  env "$process" \
    SERVER_PORT="$port" \
    YUKTA_PLUGINS_ENABLED=all \
    YUKTA_API_KEYS_0_NAME=e2e \
    YUKTA_API_KEYS_0_KEY="$key" \
    YUKTA_API_KEYS_0_ROLE=ADMIN \
    "YUKTA_API_KEYS_0_SESSIONS_0=*" \
    YUKTA_API_KEYS_1_NAME=e2e-viewer \
    YUKTA_API_KEYS_1_KEY="$viewer" \
    YUKTA_API_KEYS_1_ROLE=VIEWER \
    "YUKTA_API_KEYS_1_SESSIONS_0=*" \
    JAVA_TOOL_OPTIONS="$options" \
    nohup "$java_bin" -jar "$found" >"$dir/server.log" 2>&1 &
  echo $! >"$dir/pid"
  echo "http://127.0.0.1:$port" >"$dir/url"
  printf '%s' "$key" >"$dir/key"
  printf '%s' "$viewer" >"$dir/viewer-key"
  chmod 600 "$dir/key" "$dir/viewer-key"
  local i
  for ((i = 0; i < 90; i++)); do
    if curl -fsS "$(cat "$dir/url")/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      cat "$dir/url"
      return 0
    fi
    kill -0 "$(cat "$dir/pid")" 2>/dev/null || break
    sleep 2
  done
  echo "the server never reported UP; the end of its log:" >&2
  tail -n 40 "$dir/server.log" >&2
  stop || true
  return 1
}

stop() {
  if [ ! -f "$dir/pid" ]; then
    echo "not running" >&2
    return 0
  fi
  local pid
  pid=$(cat "$dir/pid")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    local i
    for ((i = 0; i < 30; i++)); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
  else
    echo "the server had already stopped; the end of its log:" >&2
    tail -n 40 "$dir/server.log" >&2
  fi
  rm -f "$dir/pid"
}

case ${1:-} in
  start) start ;;
  stop) stop ;;
  url) cat "$dir/url" ;;
  key) cat "$dir/key" ;;
  viewer-key) cat "$dir/viewer-key" ;;
  *)
    echo "usage: $0 start|stop|url|key|viewer-key" >&2
    exit 2
    ;;
esac
