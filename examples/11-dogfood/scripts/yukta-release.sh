#!/usr/bin/env bash
# Download and verify a published Yukta server release (default: the latest, pre-releases included)
# and print the path of its native executable. Used by `yk up --release [tag]`, which runs it.
# Downloads the native archive for this OS/arch with gh, verifies the signature of the release's
# SHA256SUMS (cosign) and the archive against it, and caches it in $DEV_HOME/releases/<tag>.
#
# Usage: yukta-release.sh [--version <tag>]     ($YUKTA_SERVER_VERSION also pins it)
# The signature must come from the release workflow or from the maintainer (the alpha.2 release was
# signed by hand). YUKTA_SKIP_VERIFY=1 skips the signature check (the checksum is still checked).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

[[ "${1:-}" != --version ]] || { YUKTA_SERVER_VERSION="${2:?--version needs a tag}"; shift 2; }
need gh
need tar
[[ -n "${YUKTA_SKIP_VERIFY:-}" ]] || need cosign   # the binary runs next to this checkout's credentials

tag="${YUKTA_SERVER_VERSION:-$(gh release list -R infenia/yukta --exclude-drafts --limit 1 --json tagName -q '.[0].tagName')}"
[[ -n "$tag" ]] || die "no published release found; use the main build (yk up) or pin --version"
tag="v${tag#v}"
version="${tag#v}"
archive="yukta-$version-$(os_arch).tar.gz"
cache="$DEV_HOME/releases/$tag"
bin="$cache/yukta-$version-$(os_arch)/yukta"

if [[ ! -x "$bin" ]]; then
  rm -rf -- "$cache"
  mkdir -p "$cache"
  gh release download "$tag" -R infenia/yukta -D "$cache" -p SHA256SUMS -p SHA256SUMS.sigstore.json -p "$archive" >&2 \
    || { rm -rf -- "$cache"; die "could not download $archive from release $tag (no native server for $(os_arch)?)"; }
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
echo "$bin"
