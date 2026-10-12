#!/usr/bin/env bash
# Stop the Yukta server started by yukta-worktree.sh and remove its worktree.
#
# Usage: yukta-stop.sh <name> [--keep-worktree]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

name="${1:?usage: yukta-stop.sh <name> [--keep-worktree]}"
pidfile="$STATE_DIR/$name.pid"
envfile="$STATE_DIR/$name.env"
[[ -f "$envfile" ]] || die "no state for '$name' in $STATE_DIR"
# shellcheck disable=SC1090
source "$envfile"

if [[ -f "$pidfile" ]]; then
  pid="$(cat "$pidfile")"
  # bootRun spawns children; stop the whole group, then the leader.
  pkill -P "$pid" 2>/dev/null || true
  kill "$pid" 2>/dev/null || true
  # Let the JVM and Gradle release the worktree before it is removed.
  for _ in $(seq 1 15); do
    pgrep -f "$YUKTA_DEV_WORKTREE" >/dev/null || break
    sleep 1
  done
  pkill -f "$YUKTA_DEV_WORKTREE" 2>/dev/null || true
  rm -f "$pidfile"
fi

if [[ "${2:-}" != --keep-worktree ]]; then
  git -C "$REPO_ROOT" worktree remove --force "$YUKTA_DEV_WORKTREE" 2>/dev/null || true
  rm -rf "$YUKTA_DEV_WORKTREE"
  git -C "$REPO_ROOT" worktree prune
  git -C "$REPO_ROOT" branch -D "yukta-dev/$name" 2>/dev/null || true
fi
rm -f "$envfile"
[[ "$(grep -s YUKTA_DEV_NAME "$STATE_DIR/current.env")" == "YUKTA_DEV_NAME=$name" ]] && rm -f "$STATE_DIR/current.env"
echo "stopped $name"
