#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Install Yukta: the server (yukta) and the command-line client (yukta-cli) in one step.
#
#   curl -fsSL https://github.com/infenia/yukta/releases/latest/download/install.sh | sh
#
# The copy attached to a release installs exactly that release. Options go after `sh -s --`:
#
#   curl -fsSL <url> | sh -s -- --version 0.1.0-rc.1 --install-dir /opt/yukta/bin
#
# Options (environment variable in brackets):
#   --version <X.Y.Z>     install this version                       [YUKTA_VERSION]
#   --install-dir <dir>   where to put the programs                  [YUKTA_INSTALL_DIR]
#                         default: /usr/local/bin as root, else ~/.local/bin
#   --cli-only            install only yukta-cli
#   --server-only         install only the server
#   --check               say whether a newer release exists; install nothing (exit 1 if behind)
#   --force               reinstall even when this version is already installed
#   --uninstall           remove yukta and yukta-cli from the install directory
#   --help
#
# What it does: downloads SHA256SUMS and the files for your system from the GitHub release,
# refuses to continue if a checksum does not match (and, when cosign is installed, if the Sigstore
# signature of SHA256SUMS does not verify), and copies the programs into place. Running it again
# upgrades: the previous programs are kept as yukta.prev and yukta-cli.prev, and to roll back you
# can move them back or pass --version <old>. It does not use sudo, change your shell profile or
# send anything anywhere but GitHub.
#
# Native server downloads exist for Linux. On macOS the script installs the client and tells you how to
# run the server with Docker, until a native macOS server is published.
# Windows: use install.ps1.

set -eu

REPO="infenia/yukta"
# release/release.sh replaces this when it attaches the script to a release.
DEFAULT_VERSION="@VERSION@"
# Test and mirror hook: a server laid out like GitHub release downloads (<base>/v<ver>/<file>).
BASE_URL="${YUKTA_DOWNLOAD_BASE:-https://github.com/$REPO/releases/download}"

say() { printf '%s\n' "$*"; }
# Animation and colour only on a terminal (and never with NO_COLOR), so pipes and logs stay plain.
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != "dumb" ]; then
  # The Yukta logo colours: orange #E85D0C and blue #2F6BFF (256-colour approximations without truecolor).
  case "${COLORTERM:-}" in
    truecolor | 24bit) O="$(printf '\033[38;2;232;93;12m')"; U="$(printf '\033[38;2;47;107;255m')" ;;
    *) O="$(printf '\033[38;5;166m')"; U="$(printf '\033[38;5;69m')" ;;
  esac
  fun=1; B="$(printf '\033[1m')"; Z="$(printf '\033[0m')"
else
  fun=0; O=""; U=""; B=""; Z=""
fi

# Run "$@" in the background and show a spinner with <label> until it ends; return its status.
spin() {
  label="$1"; shift
  if [ "$fun" = "0" ]; then "$@"; return; fi
  "$@" & job=$!
  i=0
  while kill -0 "$job" 2>/dev/null; do
    case $((i % 4)) in 0) c='|' ;; 1) c='/' ;; 2) c='-' ;; *) c='\' ;; esac
    printf '\r  %s%s%s %s ' "$U" "$c" "$Z" "$label"
    i=$((i + 1)); sleep 0.1
  done
  printf '\r\033[K'
  wait "$job"
}
err() { printf 'install: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
Install Yukta: the server (yukta) and the client (yukta-cli) in one step.

Options (environment variable in brackets):
  --version <X.Y.Z>     install this version                   [YUKTA_VERSION]
  --install-dir <dir>   where to put the programs              [YUKTA_INSTALL_DIR]
                        default: /usr/local/bin as root, else ~/.local/bin
  --cli-only            install only yukta-cli
  --server-only         install only the server
  --check               say whether a newer release exists; install nothing (exit 1 if behind)
  --force               reinstall even when this version is already installed
  --uninstall           remove yukta and yukta-cli from the install directory
  --help
USAGE
}

fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$2" "$1" || err "download failed: $1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$2" "$1" || err "download failed: $1"
  else
    err "curl or wget is required"
  fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else err "sha256sum or shasum is required to verify the download"; fi
}

# Is <file> listed in SHA256SUMS?
listed() { awk -v f="$1" '$2 == f || $2 == "*" f { found = 1 } END { exit !found }' "$tmp/SHA256SUMS"; }

# Download <file> and check it against SHA256SUMS.
get_verified() {
  spin "downloading $1" fetch "$BASE_URL/v$version/$1" "$tmp/$1" || exit 1
  want="$(awk -v f="$1" '$2 == f || $2 == "*" f { print $1; exit }' "$tmp/SHA256SUMS")"
  [ -n "$want" ] || err "$1 is not listed in SHA256SUMS"
  [ "$(sha256_of "$tmp/$1")" = "$want" ] || err "checksum mismatch for $1; not installing"
  say "  ${O}✓${Z} verified    $1"
}

# Verify the Sigstore signature of SHA256SUMS when cosign is installed; say what was checked.
verify_signature() {
  verified="checksum"
  if ! command -v cosign >/dev/null 2>&1; then
    sig_note="signature not checked (install cosign to check it)"; return
  fi
  if ! fetch_quiet "$BASE_URL/v$version/SHA256SUMS.sigstore.json" "$tmp/SHA256SUMS.sigstore.json"; then
    sig_note="signature not checked (this release has no SHA256SUMS.sigstore.json)"; return
  fi
  cosign verify-blob --bundle "$tmp/SHA256SUMS.sigstore.json" \
    --certificate-identity-regexp "^https://github.com/$REPO/" \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    "$tmp/SHA256SUMS" >/dev/null 2>&1 || err "signature of SHA256SUMS does not verify; not installing"
  verified="checksum, signature (cosign)"; sig_note=""
}

fetch_quiet() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 -o "$2" "$1" 2>/dev/null
  else wget -q -O "$2" "$1" 2>/dev/null; fi
}

# Size of a download in MB, or nothing when the server does not say.
size_mb() {
  command -v curl >/dev/null 2>&1 || return 0
  bytes="$(curl -fsSLI "$1" 2>/dev/null | awk 'tolower($1) == "content-length:" { n = $2 + 0 } END { if (n) print n }')"
  [ -z "$bytes" ] || printf '%s MB' "$((bytes / 1048576))"
}

# Version of the yukta-cli in <dir>, or nothing. The server cannot report its version.
installed_version() {
  [ -x "$1/yukta-cli" ] || return 0
  "$1/yukta-cli" --version 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?' | head -n 1 || true
}

latest_version() {
  command -v curl >/dev/null 2>&1 || err "cannot look up the latest version without curl; pass --version"
  url="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest")" ||
    err "could not look up the latest release; pass --version"
  case "$url" in
    */releases/tag/v*) printf '%s' "${url##*/tag/v}" ;;
    *) err "there is no published release yet; pass --version, or see the Quickstart for Docker" ;;
  esac
}

main() {
  version="${YUKTA_VERSION:-}"
  dir="${YUKTA_INSTALL_DIR:-}"
  do_cli=1; do_server=1; uninstall=0; check=0; force=0

  while [ $# -gt 0 ]; do
    case "$1" in
      --version) [ $# -ge 2 ] || err "--version needs a value"; version="$2"; shift ;;
      --install-dir) [ $# -ge 2 ] || err "--install-dir needs a value"; dir="$2"; shift ;;
      --cli-only) do_server=0 ;;
      --server-only) do_cli=0 ;;
      --check) check=1 ;;
      --force) force=1 ;;
      --uninstall) uninstall=1 ;;
      -h | --help) usage; exit 0 ;;
      *) err "unknown option $1 (see --help)" ;;
    esac
    shift
  done
  [ "$do_cli$do_server" != "00" ] || err "--cli-only and --server-only cannot be combined"

  if [ -z "$dir" ]; then
    if [ "$(id -u)" = "0" ]; then dir=/usr/local/bin; else dir="${HOME:?HOME is not set}/.local/bin"; fi
  fi

  if [ "$uninstall" = "1" ]; then
    for f in yukta yukta-cli; do
      if [ -f "$dir/$f" ]; then rm -f "$dir/$f" && say "removed $dir/$f"; fi
    done
    exit 0
  fi

  os="${YUKTA_OS:-$(uname -s)}"
  case "$os" in
    Linux | linux) os=linux ;;
    Darwin | darwin) os=darwin ;;
    MINGW* | MSYS* | CYGWIN*) err "on Windows use install.ps1 (PowerShell)" ;;
    *) err "unsupported operating system: $os" ;;
  esac
  arch="${YUKTA_ARCH:-$(uname -m)}"
  case "$arch" in
    x86_64 | amd64) arch=amd64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) err "unsupported processor: $arch" ;;
  esac

  if [ -z "$version" ] && [ "$DEFAULT_VERSION" != "@VERSION@" ]; then version="$DEFAULT_VERSION"; fi
  if [ -z "$version" ]; then version="$(latest_version)"; fi
  version="${version#v}"
  # The version goes into URLs and file names, so it must be one: X.Y.Z or X.Y.Z-rc.1, nothing else
  # (no "/", "..", "?" or "#", no newline). release.sh checks the same pattern.
  case "$version" in
    *[!0-9A-Za-z.-]* | "") err "'$version' is not a version (expected X.Y.Z or X.Y.Z-rc.1)" ;;
  esac
  printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' ||
    err "'$version' is not a version (expected X.Y.Z or X.Y.Z-rc.1)"

  found="$(installed_version "$dir")"
  if [ "$check" = "1" ]; then
    say "installed: ${found:-none in $dir}"
    say "latest:    $version"
    [ "$found" != "$version" ] || exit 0
    say "A newer release is available; run this script again to upgrade."
    exit 1
  fi
  if [ "$found" = "$version" ] && [ "$force" = "0" ]; then
    say "Yukta $version is already installed in $dir (use --force to reinstall)."
    exit 0
  fi

  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT INT TERM

  say "${U}${B}Yukta installer${Z}"
  if [ -n "$found" ]; then say "  found:      $found in $dir"; elif [ -e "$dir/yukta" ]; then say "  found:      an unknown version in $dir"; fi
  size=""
  [ "$do_server" = "0" ] || size="$(size_mb "$BASE_URL/v$version/yukta-$version-$os-$arch.tar.gz")"
  say "  installing: $version  ($os/$arch${size:+, $size})  into $dir"
  fetch "$BASE_URL/v$version/SHA256SUMS" "$tmp/SHA256SUMS"
  verify_signature

  mkdir -p "$dir" 2>/dev/null || true
  [ -d "$dir" ] && [ -w "$dir" ] ||
    err "cannot write to $dir; choose another with --install-dir, or run as root"

  keep_prev() { if [ -f "$dir/$1" ]; then cp -p "$dir/$1" "$dir/$1.prev"; prev_kept=1; fi; }
  prev_kept=0
  archive="yukta-$version-$os-$arch.tar.gz"
  cli_asset="yukta-cli-$os-$arch"
  installed_server=0

  if listed "$archive"; then
    get_verified "$archive"
    tar -xzf "$tmp/$archive" -C "$tmp"
    src="$tmp/yukta-$version-$os-$arch"
    # Install only regular files that the archive put in its own folder, never a link or a stray path.
    for f in yukta yukta-cli; do
      [ -f "$src/$f" ] && [ ! -L "$src/$f" ] || err "$archive does not contain a regular file $f; not installing"
    done
    [ "$do_server" = "1" ] && { keep_prev yukta; install -m 0755 "$src/yukta" "$dir/yukta"; installed_server=1; }
    [ "$do_cli" = "1" ] && { keep_prev yukta-cli; install -m 0755 "$src/yukta-cli" "$dir/yukta-cli"; }
  else
    [ "$do_server" = "0" ] || [ "$do_cli" = "1" ] || err "this release has no native server for $os/$arch; use Docker"
    if [ "$do_cli" = "1" ]; then
      get_verified "$cli_asset"
      keep_prev yukta-cli
      install -m 0755 "$tmp/$cli_asset" "$dir/yukta-cli"
    fi
  fi

  say ""
  [ "$do_server" = "0" ] || [ "$installed_server" = "1" ] ||
    say "Note: this release has no native server for $os/$arch yet. Run the server with Docker instead:
  docker run --rm -p 127.0.0.1:8080:8080 -e YUKTA_API_KEY=change-me ghcr.io/infenia/yukta:$version"
  say "  verified:   $verified${sig_note:+  [$sig_note]}"
  [ "$installed_server" = "0" ] || say "Installed: $dir/yukta (server) $version"
  [ "$do_cli" = "0" ] || say "Installed: $dir/yukta-cli (client) $version"
  if [ "$prev_kept" = "1" ]; then
    say "Previous version kept as *.prev; to roll back move them back, or run again with --version <old>."
  fi
  if [ "$installed_server" = "1" ] && command -v pgrep >/dev/null 2>&1 && pgrep -x yukta >/dev/null 2>&1; then
    say ""
    say "Note: a yukta server is running and keeps running the old version. Stop it, start the new one"
    say "and re-apply your sessions; sessions and runs kept in memory are lost on restart (set"
    say "YUKTA_BOOTSTRAP_DIR to reload them; see https://yukta.infenia.com/operations/persistence)."
  fi
  for f in yukta yukta-cli; do
    [ -f "$dir/$f" ] || continue
    other="$(command -v "$f" 2>/dev/null || true)"
    [ -z "$other" ] || [ "$other" = "$dir/$f" ] ||
      say "Warning: $other comes first on your PATH and hides the $f just installed."
  done

  case ":$PATH:" in
    *":$dir:"*) ;;
    *) say ""; say "$dir is not on your PATH. Add it, for example:"; say "  export PATH=\"$dir:\$PATH\"" ;;
  esac

  say ""
  say "${O}${B}Done!${Z} Yukta $version is ready."
  say "Next:"
  [ "$installed_server" = "0" ] || say "  yukta                          # start the server on http://localhost:8080"
  [ "$do_cli" = "0" ] || say "  yukta-cli session list         # talk to a running server"
}

main "$@"
