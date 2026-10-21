#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Sign release artifacts with keyless Sigstore signatures (cosign). Nothing secret is stored: cosign
# asks you to sign in with your identity in a browser, and the signature is recorded in a public log.
# In GitHub Actions the identity is the workflow instead.
#
# Usage: release/sign.sh <version> [--images]
#   signs dist/assets/SHA256SUMS (made by release.sh) as SHA256SUMS.sigstore.json, and with --images the image digests
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 ]] || die "usage: sign.sh <version> [--images]"
version="$(normalize_version "$1")"
need cosign

# One signature, on SHA256SUMS, covers every file: it lists their hashes, and each signature is a
# browser sign-in. Verifiers check the checksums file, then the files against it.
checksums="$DIST/assets/SHA256SUMS"
[[ -f "$checksums" ]] || die "missing $checksums (run release/release.sh first)"
log "signing $checksums"
cosign sign-blob --yes --bundle "$checksums.sigstore.json" "$checksums"

if [[ "${2:-}" == "--images" ]]; then
  need docker
  for image in "${IMAGES[@]}"; do
    digest="$(docker buildx imagetools inspect "$image:$version" --format '{{json .Manifest.Digest}}' | tr -d '"')"
    log "signing $image@$digest"
    cosign sign --yes "$image@$digest"
  done
fi
