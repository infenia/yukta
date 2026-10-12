#!/usr/bin/env bash
# Shared helpers for the dogfood scripts. Sourced, not executed.

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
STATE_DIR="${YUKTA_DEV_STATE_DIR:-$REPO_ROOT/.yukta-dev}"

die() { echo "error: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not on PATH"; }

# Locate the yukta CLI: $YUKTA_BIN, then PATH, then the newest local build in cli/build.
find_cli() {
  if [[ -n "${YUKTA_BIN:-}" ]]; then echo "$YUKTA_BIN"; return; fi
  if command -v yukta >/dev/null 2>&1; then command -v yukta; return; fi
  # Newest build wins: an old cli/build/yukta from a previous layout may still be lying around.
  local f
  f="$(ls -t "$REPO_ROOT"/cli/build/yukta "$REPO_ROOT"/cli/build/yukta-* 2>/dev/null | head -1)"
  [[ -n "$f" && -x "$f" ]] && { echo "$f"; return; }
  die "yukta CLI not found; run ./gradlew :cli:goBuild or set YUKTA_BIN"
}
