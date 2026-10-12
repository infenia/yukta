#!/usr/bin/env bash
# Create a worktree from main and start a Yukta server from it on a free port.
#
# Usage: yukta-worktree.sh <name> [base-ref]
#   <name>      worktree/branch suffix; creates branch yukta-dev/<name> and ../yukta-dev-<name>
#   [base-ref]  ref to branch from (default: origin/main, fetched first; a local main can be stale)
#
# Waits up to $YUKTA_STARTUP_TIMEOUT seconds (default 120) for the server to become healthy.
# Starting several servers at the same moment is not supported (port choice is not locked).
# Writes .yukta-dev/<name>.env (and .yukta-dev/current.env) with YUKTA_SERVER_URL etc.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

name="${1:?usage: yukta-worktree.sh <name> [base-ref]}"
valid_name "$name"
base="${2:-origin/main}"
need curl

wt="$(dirname "$REPO_ROOT")/yukta-dev-$name"
branch="yukta-dev/$name"
mkdir -p "$STATE_DIR"

if [[ ! -e "$wt" ]]; then
  [[ "$base" == origin/* ]] && git -C "$REPO_ROOT" fetch -q origin "${base#origin/}"
  git -C "$REPO_ROOT" worktree add -b "$branch" "$wt" "$base"
else
  # Reuse only a real worktree of our branch that already contains the base ($base is ignored here).
  git -C "$REPO_ROOT" worktree list --porcelain | grep -qxF "worktree $wt" \
    || die "$wt exists but is not a git worktree; remove it or pick another name"
  [[ "$(git -C "$wt" rev-parse --abbrev-ref HEAD)" == "$branch" ]] \
    || die "worktree $wt is on '$(git -C "$wt" rev-parse --abbrev-ref HEAD)', expected $branch"
  git -C "$wt" merge-base --is-ancestor "$base" HEAD 2>/dev/null \
    || die "worktree $wt is at $(git -C "$wt" rev-parse --short HEAD) and lacks $base; run yukta-stop.sh $name first"
  [[ -z "$(git -C "$wt" status --porcelain --untracked-files=no)" ]] \
    || echo "warning: $wt has uncommitted changes; the server runs them" >&2
fi

# The ui build needs the node/pnpm toolchain it downloaded into ui/.gradle; reuse it instead of
# failing in a fresh worktree.
if [[ -d "$REPO_ROOT/ui/.gradle" && ! -d "$wt/ui/.gradle" ]]; then
  cp -r --reflink=auto "$REPO_ROOT/ui/.gradle" "$wt/ui/.gradle"
fi

# First free port from 9101 upward.
port=9101
while (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; do port=$((port + 1)); done

url="http://localhost:$port"
log="$STATE_DIR/$name.log"
# Own session/process group (setsid) so yukta-stop.sh can stop the Gradle wrapper and the JVM
# together. The shell records its own pid, then execs Gradle, so the pid is the group leader.
(
  cd "$wt"
  export SERVER_PORT="$port" YUKTA_SESSION_BASE_DIR="$wt/.yukta" \
    YUKTA_SESSION_EXECUTION_TIMEOUT_SECONDS=14400
  setsid bash -c 'echo $$ >"$1"; exec ./gradlew bootRun -q --console=plain' _ \
    "$STATE_DIR/$name.pid" >"$log" 2>&1 </dev/null &
)
for _ in 1 2 3 4 5; do [[ -s "$STATE_DIR/$name.pid" ]] && break; sleep 1; done

cat >"$STATE_DIR/$name.env" <<ENV
YUKTA_DEV_NAME=$name
YUKTA_DEV_WORKTREE=$wt
YUKTA_DEV_BASE=$base
YUKTA_SERVER_URL=$url
ENV
cp "$STATE_DIR/$name.env" "$STATE_DIR/current.env"

echo "waiting for Yukta at $url (log: $log) ..."
timeout_s="${YUKTA_STARTUP_TIMEOUT:-120}"
for _ in $(seq 1 $((timeout_s / 2))); do
  kill -0 "$(cat "$STATE_DIR/$name.pid")" 2>/dev/null || die "server exited early; see $log"
  if curl -fsS "$url/actuator/health" >/dev/null 2>&1; then
    echo "ready: export YUKTA_SERVER_URL=$url"
    exit 0
  fi
  sleep 2
done
die "server did not become healthy in ${timeout_s}s; see $log"
