#!/usr/bin/env bash
# Start or stop the Yukta server the dogfood scripts talk to. This is the one entry point the
# `yukta` skill uses, so how the server is launched can change without changing the skill.
#
# Usage: yukta-server.sh start|stop <name> [provider args...]
#   worktree (default)  a git worktree of origin/main run with bootRun (yukta-worktree.sh /
#                       yukta-stop.sh; extra args are passed through, e.g. a base ref or --force)
#   native              the GraalVM native executable (yukta-native.sh): seconds instead of minutes
# The provider is $YUKTA_SERVER_MODE, else native when $YUKTA_SERVER_BIN is set, else worktree.
# Every provider writes .yukta-dev/<name>.env and current.env, which yukta-check.sh, yukta-watch.sh
# and yukta-cli.sh read, and prints "ready: export YUKTA_SERVER_URL=<url>" when the server is up.
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
  mode="${YUKTA_SERVER_MODE:-$([[ -n "${YUKTA_SERVER_BIN:-}" ]] && echo native || echo worktree)}"
fi

case "$mode:$action" in
  worktree:start) exec "$here/yukta-worktree.sh" "$name" "$@" ;;
  worktree:stop) exec "$here/yukta-stop.sh" "$name" "$@" ;;
  native:start | native:stop) exec "$here/yukta-native.sh" "$action" "$name" "$@" ;;
  *) die "unknown mode/action '$mode:$action' (modes: worktree, native; actions: start, stop)" ;;
esac
