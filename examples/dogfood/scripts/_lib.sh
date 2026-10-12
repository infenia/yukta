#!/usr/bin/env bash
# Shared helpers for the dogfood scripts. Sourced, not executed.

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
STATE_DIR="${YUKTA_DEV_STATE_DIR:-$REPO_ROOT/.yukta-dev}"

die() { echo "error: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not on PATH"; }

# Locate the yukta CLI: $YUKTA_BIN, then PATH, then a local gradle build.
find_cli() {
  if [[ -n "${YUKTA_BIN:-}" ]]; then echo "$YUKTA_BIN"; return; fi
  if command -v yukta >/dev/null 2>&1; then command -v yukta; return; fi
  local f
  for f in "$REPO_ROOT"/cli/build/yukta "$REPO_ROOT"/cli/build/yukta-*; do
    [[ -x "$f" ]] && { echo "$f"; return; }
  done
  die "yukta CLI not found; run ./gradlew :cli:goBuild or set YUKTA_BIN"
}
