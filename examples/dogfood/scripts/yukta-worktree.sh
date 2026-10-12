#!/usr/bin/env bash
# Create a worktree from main and start a Yukta server from it on a free port.
#
# Usage: yukta-worktree.sh <name> [base-ref]
#   <name>      worktree/branch suffix; creates branch yukta-dev/<name> and ../yukta-dev-<name>
#   [base-ref]  ref to branch from (default: main)
#
# Writes .yukta-dev/<name>.env (and .yukta-dev/current.env) with YUKTA_SERVER_URL etc.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

name="${1:?usage: yukta-worktree.sh <name> [base-ref]}"
base="${2:-main}"
need curl

wt="$(dirname "$REPO_ROOT")/yukta-dev-$name"
branch="yukta-dev/$name"
mkdir -p "$STATE_DIR"

if [[ ! -d "$wt" ]]; then
  git -C "$REPO_ROOT" worktree add -b "$branch" "$wt" "$base"
fi

# First free port from 9101 upward.
port=9101
while (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; do port=$((port + 1)); done

url="http://localhost:$port"
log="$STATE_DIR/$name.log"
(
  cd "$wt"
  SERVER_PORT="$port" YUKTA_SESSION_BASE_DIR="$wt/.yukta" \
    YUKTA_SESSION_EXECUTION_TIMEOUT_SECONDS=14400 \
    nohup ./gradlew bootRun -q --console=plain >"$log" 2>&1 &
  echo $! >"$STATE_DIR/$name.pid"
)

cat >"$STATE_DIR/$name.env" <<ENV
YUKTA_DEV_NAME=$name
YUKTA_DEV_WORKTREE=$wt
YUKTA_SERVER_URL=$url
ENV
cp "$STATE_DIR/$name.env" "$STATE_DIR/current.env"

echo "waiting for Yukta at $url (log: $log) ..."
for _ in $(seq 1 120); do
  if curl -fsS "$url/actuator/health" >/dev/null 2>&1; then
    echo "ready: export YUKTA_SERVER_URL=$url"
    exit 0
  fi
  sleep 5
done
die "server did not become healthy in 10 minutes; see $log"
