#!/usr/bin/env bash
# Stop the Yukta server started by yukta-worktree.sh and remove its worktree and branch.
#
# Usage: yukta-stop.sh <name> [--keep-worktree] [--force]
#   --keep-worktree  stop the server only
#   --force          remove the worktree even with uncommitted changes or unmerged commits
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

name="${1:?usage: yukta-stop.sh <name> [--keep-worktree] [--force]}"
valid_name "$name"
shift
keep=0 force=0
for a in "$@"; do
  case "$a" in
    --keep-worktree) keep=1 ;;
    --force) force=1 ;;
    *) die "unknown option '$a'" ;;
  esac
done

envfile="$STATE_DIR/$name.env"
[[ -f "$envfile" ]] || die "no state for '$name' in $STATE_DIR"
# shellcheck disable=SC1090
source "$envfile"

wt="${YUKTA_DEV_WORKTREE:-}"
branch="yukta-dev/$name"
[[ "$wt" == */yukta-dev-"$name" ]] || die "refusing to touch '$wt' (expected a path ending in yukta-dev-$name)"

# Check before stopping anything, so a refusal leaves the server running.
if (( ! keep && ! force )) && [[ -d "$wt" ]]; then
  [[ -z "$(git -C "$wt" status --porcelain --untracked-files=no)" ]] \
    || die "$wt has uncommitted changes; commit them or use --force"
  base="${YUKTA_DEV_BASE:-origin/main}"
  [[ -z "$(git -C "$REPO_ROOT" log --oneline "$base..$branch" 2>/dev/null)" ]] \
    || die "$branch has commits not in $base; merge or push them, or use --force"
fi

stop_server "$name"

if (( ! keep )); then
  git -C "$REPO_ROOT" worktree remove --force "$wt" 2>/dev/null || true
  [[ -e "$wt" ]] && rm -rf -- "$wt"
  git -C "$REPO_ROOT" worktree prune
  git -C "$REPO_ROOT" branch -D "$branch" 2>/dev/null || true
fi
forget_server "$name"
echo "stopped $name"
