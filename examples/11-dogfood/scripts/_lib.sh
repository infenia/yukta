#!/usr/bin/env bash
# Shared helpers for the dogfood scripts. Sourced, not executed.

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
STATE_DIR="${YUKTA_DEV_STATE_DIR:-$REPO_ROOT/.yukta-dev}"

die() { echo "error: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not on PATH"; }

# valid_name NAME: names end up in file paths, a branch and a worktree path, so keep them plain.
valid_name() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "invalid name '$1' (use letters, digits, . _ -)"
}

# Locate the yukta CLI: $YUKTA_BIN, then PATH, then the newest build for this OS/arch in cli/build
# (yukta-<os>-<arch> from `gradlew :cli:goBuild`, or yukta from `make build`).
find_cli() {
  if [[ -n "${YUKTA_BIN:-}" ]]; then echo "$YUKTA_BIN"; return; fi
  if command -v yukta >/dev/null 2>&1; then command -v yukta; return; fi
  local os arch f
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  case "$(uname -m)" in
    x86_64 | amd64) arch=amd64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) arch="$(uname -m)" ;;
  esac
  f="$(ls -t "$REPO_ROOT/cli/build/yukta-$os-$arch" "$REPO_ROOT/cli/build/yukta" 2>/dev/null | head -1)"
  [[ -n "$f" && -x "$f" ]] && { echo "$f"; return; }
  die "yukta CLI for $os/$arch not found; run ./gradlew :cli:goBuild or set YUKTA_BIN"
}

# render_session TEMPLATE PROJECT_PATH: prints the session JSON with __PROJECT_PATH__ replaced.
# Done on parsed JSON so any characters in the path are escaped correctly.
render_session() {
  jq --arg p "$2" 'walk(if type == "string" then split("__PROJECT_PATH__") | join($p) else . end)' "$1"
}
