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
#   --uninstall           remove yukta and yukta-cli from the install directory
#   --help
#
# What it does: downloads SHA256SUMS and the files for your system from the GitHub release,
# refuses to continue if a checksum does not match, and copies the programs into place. It does
# not use sudo, change your shell profile or send anything anywhere but GitHub.
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
  fetch "$BASE_URL/v$version/$1" "$tmp/$1"
  want="$(awk -v f="$1" '$2 == f || $2 == "*" f { print $1; exit }' "$tmp/SHA256SUMS")"
  [ -n "$want" ] || err "$1 is not listed in SHA256SUMS"
  [ "$(sha256_of "$tmp/$1")" = "$want" ] || err "checksum mismatch for $1; not installing"
  say "  verified $1"
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
  do_cli=1; do_server=1; uninstall=0

  while [ $# -gt 0 ]; do
    case "$1" in
      --version) [ $# -ge 2 ] || err "--version needs a value"; version="$2"; shift ;;
      --install-dir) [ $# -ge 2 ] || err "--install-dir needs a value"; dir="$2"; shift ;;
      --cli-only) do_server=0 ;;
      --server-only) do_cli=0 ;;
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

  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT INT TERM

  say "Installing Yukta $version for $os/$arch into $dir"
  fetch "$BASE_URL/v$version/SHA256SUMS" "$tmp/SHA256SUMS"

  mkdir -p "$dir" 2>/dev/null || true
  [ -d "$dir" ] && [ -w "$dir" ] ||
    err "cannot write to $dir; choose another with --install-dir, or run as root"

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
    [ "$do_server" = "1" ] && { install -m 0755 "$src/yukta" "$dir/yukta"; installed_server=1; }
    [ "$do_cli" = "1" ] && install -m 0755 "$src/yukta-cli" "$dir/yukta-cli"
  else
    [ "$do_server" = "0" ] || [ "$do_cli" = "1" ] || err "this release has no native server for $os/$arch; use Docker"
    if [ "$do_cli" = "1" ]; then
      get_verified "$cli_asset"
      install -m 0755 "$tmp/$cli_asset" "$dir/yukta-cli"
    fi
  fi

  say ""
  [ "$do_server" = "0" ] || [ "$installed_server" = "1" ] ||
    say "Note: this release has no native server for $os/$arch yet. Run the server with Docker instead:
  docker run --rm -p 127.0.0.1:8080:8080 -e YUKTA_API_KEY=change-me ghcr.io/infenia/yukta:$version"
  [ "$installed_server" = "0" ] || say "Installed: $dir/yukta (server)"
  [ "$do_cli" = "0" ] || say "Installed: $dir/yukta-cli (client)"

  case ":$PATH:" in
    *":$dir:"*) ;;
    *) say ""; say "$dir is not on your PATH. Add it, for example:"; say "  export PATH=\"$dir:\$PATH\"" ;;
  esac

  say ""
  say "Next:"
  [ "$installed_server" = "0" ] || say "  yukta                          # start the server on http://localhost:8080"
  [ "$do_cli" = "0" ] || say "  yukta-cli session list         # talk to a running server"
}

main "$@"
