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
#
# REPO_ROOT may itself be a fresh worktree without the toolchain, so also try the main checkout
# (the parent of the common git dir). Copy each entry that is missing: that works when the
# destination already exists (a failed earlier build leaves a partial one) and never nests
# ui/.gradle/.gradle.
main_root="$(dirname "$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir)")"
for src in "$REPO_ROOT/ui/.gradle" "$main_root/ui/.gradle"; do
  if [[ -d "$src/pnpm" ]]; then
    mkdir -p "$wt/ui/.gradle"
    for d in "$src"/*; do
      [[ -e "$wt/ui/.gradle/${d##*/}" ]] || cp -r --reflink=auto "$d" "$wt/ui/.gradle/"
    done
    break
  fi
done

port="$(free_port)"
url="http://localhost:$port"
# launch_server gives the server its own process group, so yukta-stop.sh stops the Gradle wrapper
# and the JVM together.
export SERVER_PORT="$port" YUKTA_SESSION_BASE_DIR="$wt/.yukta" \
  YUKTA_SESSION_EXECUTION_TIMEOUT_SECONDS=14400 YUKTA_PROCESS_ALLOW_ANY=true \
  YUKTA_SUPERVISOR_RULES_FILE="$REPO_ROOT/examples/11-dogfood/rules.json"
launch_server "$name" "$wt" ./gradlew bootRun -q --console=plain

cat >"$STATE_DIR/$name.env" <<ENV
YUKTA_DEV_NAME=$name
YUKTA_DEV_WORKTREE=$wt
YUKTA_DEV_BASE=$base
YUKTA_SERVER_URL=$url
ENV
cp "$STATE_DIR/$name.env" "$STATE_DIR/current.env"

wait_healthy "$name" "$url" "${YUKTA_STARTUP_TIMEOUT:-120}"
