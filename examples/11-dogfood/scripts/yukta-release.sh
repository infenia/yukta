#!/usr/bin/env bash
# Start a Yukta server from a published GitHub release (default: the latest, pre-releases included).
# Usually reached through yukta-server.sh. Downloads the native archive for this OS/arch with gh,
# verifies it against the release's SHA256SUMS, caches it in .yukta-dev/releases/<tag>, and hands the
# executable to yukta-native.sh, which owns start, stop and the state files.
#
# Usage: yukta-release.sh start <name> [--version <tag>]     ($YUKTA_SERVER_VERSION also pins it)
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
source "$here/_lib.sh"

[[ "${1:-}" == start && -n "${2:-}" ]] || die "usage: yukta-release.sh start <name> [--version <tag>]"
name="$2"
shift 2
[[ "${1:-}" != --version ]] || { YUKTA_SERVER_VERSION="${2:?--version needs a tag}"; shift 2; }
need gh
need tar

tag="${YUKTA_SERVER_VERSION:-$(gh release list -R infenia/yukta --exclude-drafts --limit 1 --json tagName -q '.[0].tagName')}"
[[ -n "$tag" ]] || die "no published release found; build one locally (YUKTA_SERVER_MODE=native|jar) or pin --version"
tag="v${tag#v}"
version="${tag#v}"
os="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$(uname -m)" in x86_64 | amd64) arch=amd64 ;; aarch64 | arm64) arch=arm64 ;; *) arch="$(uname -m)" ;; esac
archive="yukta-$version-$os-$arch.tar.gz"
cache="$STATE_DIR/releases/$tag"
bin="$cache/yukta-$version-$os-$arch/yukta"

if [[ ! -x "$bin" ]]; then
  rm -rf -- "$cache"
  mkdir -p "$cache"
  gh release download "$tag" -R infenia/yukta -D "$cache" -p SHA256SUMS -p "$archive" \
    || { rm -rf -- "$cache"; die "could not download $archive from release $tag (no native server for $os/$arch?)"; }
  (cd "$cache" && grep -F " $archive" SHA256SUMS | sha256sum -c --quiet -) \
    || { rm -rf -- "$cache"; die "checksum mismatch for $archive; not using it"; }
  tar -xzf "$cache/$archive" -C "$cache"
fi

YUKTA_SERVER_BIN="$bin" YUKTA_SERVER_LABEL="release $tag" exec "$here/yukta-native.sh" start "$name"
