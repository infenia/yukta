#!/usr/bin/env bash
# Start a Yukta server from a published GitHub release (default: the latest, pre-releases included).
# Usually reached through yukta-server.sh. Downloads the native archive for this OS/arch with gh,
# verifies the signature of the release's SHA256SUMS (cosign) and the archive against it, caches it in
# .yukta-dev/releases/<tag>, and hands the
# executable to yukta-native.sh, which owns start, stop and the state files.
#
# Usage: yukta-release.sh start <name> [--version <tag>]     ($YUKTA_SERVER_VERSION also pins it)
# The signature must come from the release workflow or from the maintainer (the alpha.2 release was
# signed by hand). YUKTA_SKIP_VERIFY=1 skips the signature check (the checksum is still checked).
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
source "$here/_lib.sh"

[[ "${1:-}" == start && -n "${2:-}" ]] || die "usage: yukta-release.sh start <name> [--version <tag>]"
name="$2"
shift 2
[[ "${1:-}" != --version ]] || { YUKTA_SERVER_VERSION="${2:?--version needs a tag}"; shift 2; }
need gh
need tar
[[ -n "${YUKTA_SKIP_VERIFY:-}" ]] || need cosign   # the binary runs next to this checkout's credentials

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
  gh release download "$tag" -R infenia/yukta -D "$cache" -p SHA256SUMS -p SHA256SUMS.sigstore.json -p "$archive" \
    || { rm -rf -- "$cache"; die "could not download $archive from release $tag (no native server for $os/$arch?)"; }
  if [[ -z "${YUKTA_SKIP_VERIFY:-}" ]]; then
    (cd "$cache" && cosign verify-blob --bundle SHA256SUMS.sigstore.json SHA256SUMS \
      --certificate-identity-regexp '^(https://github\.com/infenia/yukta/\.github/workflows/release\.yml@refs/tags/v.*|arun@infenia\.com)$' \
      --certificate-oidc-issuer-regexp '^(https://token\.actions\.githubusercontent\.com|https://github\.com/login/oauth)$' >/dev/null 2>&1) \
      || { rm -rf -- "$cache"; die "the signature of SHA256SUMS in release $tag does not verify; not using it"; }
  fi
  (cd "$cache" && grep -F " $archive" SHA256SUMS | sha256sum -c --quiet -) \
    || { rm -rf -- "$cache"; die "checksum mismatch for $archive; not using it"; }
  tar -xzf "$cache/$archive" -C "$cache"
fi

YUKTA_SERVER_BIN="$bin" YUKTA_SERVER_LABEL="release $tag" exec "$here/yukta-native.sh" start "$name"
