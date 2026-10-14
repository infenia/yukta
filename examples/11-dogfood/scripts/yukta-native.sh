#!/usr/bin/env bash
# Start or stop a Yukta server from the GraalVM native executable (no Gradle, no worktree).
# Usually reached through yukta-server.sh; same state files as yukta-worktree.sh, so
# yukta-check.sh, yukta-watch.sh and yukta-cli.sh find the server unchanged.
#
# Usage: yukta-native.sh start <name> | stop <name>
#   YUKTA_SERVER_BIN  the executable (default: boot/build/native/nativeCompile/yukta, built with
#                     ./gradlew :boot:nativeCompile)
#   YUKTA_STARTUP_TIMEOUT  seconds to wait for health (default 60)
# The server's sessions live in .yukta-dev/native-<name>/ and are removed by stop.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

action="${1:?usage: yukta-native.sh start|stop <name>}"
name="${2:?usage: yukta-native.sh start|stop <name>}"
valid_name "$name"
data="$STATE_DIR/native-$name"

case "$action" in
  start)
    bin="${YUKTA_SERVER_BIN:-$REPO_ROOT/boot/build/native/nativeCompile/yukta}"
    [[ -x "$bin" ]] || die "no native executable at $bin; run ./gradlew :boot:nativeCompile or set YUKTA_SERVER_BIN"
    if [[ -s "$STATE_DIR/$name.pid" ]] && kill -0 "$(cat "$STATE_DIR/$name.pid")" 2>/dev/null; then
      die "'$name' is already running; run yukta-native.sh stop $name first"
    fi
    need curl
    mkdir -p "$data"
    port="$(free_port)"
    url="http://localhost:$port"
    export SERVER_PORT="$port" YUKTA_SESSION_BASE_DIR="$data/sessions" \
      YUKTA_SESSION_EXECUTION_TIMEOUT_SECONDS=14400 YUKTA_PROCESS_ALLOW_ANY=true
    launch_server "$name" "$data" "$bin"
    cat >"$STATE_DIR/$name.env" <<ENV
YUKTA_DEV_NAME=$name
YUKTA_DEV_MODE=native
YUKTA_SERVER_URL=$url
ENV
    cp "$STATE_DIR/$name.env" "$STATE_DIR/current.env"
    wait_healthy "$name" "$url" "${YUKTA_STARTUP_TIMEOUT:-60}"
    ;;
  stop)
    [[ -f "$STATE_DIR/$name.env" ]] || die "no state for '$name' in $STATE_DIR"
    stop_server "$name"
    forget_server "$name"
    [[ -d "$data" ]] && rm -rf -- "$data"
    echo "stopped $name"
    ;;
  *) die "unknown action '$action' (use start or stop)" ;;
esac
