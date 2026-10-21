#!/usr/bin/env bash
# Start or stop the Yukta server the dogfood scripts talk to. This is the one entry point the
# `yukta` skill uses, so how the server is launched can change without changing the skill.
#
# Usage: yukta-server.sh start|stop <name> [--version <tag>] [provider args...]
#   release (default)   the latest GitHub release's native executable (yukta-release.sh), checksum-verified
#                       and cached; --version <tag> or $YUKTA_SERVER_VERSION pins one
#   native              a local GraalVM executable (yukta-native.sh): ./gradlew :boot:nativeCompile
#   jar                 a local boot jar run with java -jar: ./gradlew :boot:bootJar
#   docker              a local image from release/build-image.sh (no JDK inside: no gradle stages)
#   worktree            a git worktree of origin/main run with bootRun (minutes; opt-in)
# The provider is $YUKTA_SERVER_MODE, else native when $YUKTA_SERVER_BIN is set, else release.
# Every provider writes .yukta-dev/<name>.env and current.env, which yukta-check.sh, yukta-watch.sh
# and yukta-cli.sh read, and prints "ready: export YUKTA_SERVER_URL=<url> (<what it runs>)" when up.
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
source "$here/_lib.sh"

action="${1:?usage: yukta-server.sh start|stop <name> [provider args...]}"
name="${2:?usage: yukta-server.sh start|stop <name> [provider args...]}"
shift 2
valid_name "$name"
envfile="$STATE_DIR/$name.env"
if [[ "$action" == stop && -f "$envfile" ]]; then
  # Stop with the provider that started it, whatever the environment says now.
  mode="$(sed -n 's/^YUKTA_DEV_MODE=//p' "$envfile")"
  mode="${mode:-worktree}"
else
  [[ "$action" != start || ! -e "$envfile" ]] || die "'$name' already exists; stop it first (yukta-server.sh stop $name)"
  mode="${YUKTA_SERVER_MODE:-$([[ -n "${YUKTA_SERVER_BIN:-}" ]] && echo native || echo release)}"
fi

case "$mode:$action" in
  release:start) exec "$here/yukta-release.sh" start "$name" "$@" ;;
  jar:start)
    YUKTA_SERVER_JAR="${YUKTA_SERVER_JAR:-$(ls -t "$REPO_ROOT"/boot/build/libs/*.jar 2>/dev/null | grep -v plain | head -1 || true)}"
    [[ -n "$YUKTA_SERVER_JAR" ]] || die "no boot jar; run ./gradlew :boot:bootJar or set YUKTA_SERVER_JAR"
    export YUKTA_SERVER_JAR
    exec "$here/yukta-native.sh" start "$name" "$@" ;;
  docker:start | docker:stop) exec "$here/yukta-docker.sh" "$action" "$name" "$@" ;;
  worktree:start) exec "$here/yukta-worktree.sh" "$name" "$@" ;;
  worktree:stop) exec "$here/yukta-stop.sh" "$name" "$@" ;;
  native:start | native:stop) exec "$here/yukta-native.sh" "$action" "$name" "$@" ;;
  *) die "unknown mode/action '$mode:$action' (modes: release, native, jar, docker, worktree; actions: start, stop)" ;;
esac
