#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Sign release artifacts with keyless Sigstore signatures (cosign). Nothing secret is stored: cosign
# asks you to sign in with your identity in a browser, and the signature is recorded in a public log.
# In GitHub Actions the identity is the workflow instead.
#
# Usage: release/sign.sh <version> [--images]
#   signs dist/packages/* and the SHA256SUMS files as <file>.bundle, and with --images the image digests
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 ]] || die "usage: sign.sh <version> [--images]"
version="$(normalize_version "$1")"
need cosign

while IFS= read -r -d '' file; do
  log "signing $file"
  cosign sign-blob --yes --bundle "$file.bundle" "$file"
done < <(find "$DIST/packages" "$DIST/cli" -maxdepth 1 -type f \( -name '*.tar.gz' -o -name '*.deb' -o -name '*.rpm' -o -name 'SHA256SUMS' -o -name 'yukta-cli-*' \) ! -name '*.bundle' -print0)

if [[ "${2:-}" == "--images" ]]; then
  need docker
  for image in "$IMAGE_GHCR" "$IMAGE_HUB"; do
    digest="$(docker buildx imagetools inspect "$image:$version" --format '{{json .Manifest.Digest}}' | tr -d '"')"
    log "signing $image@$digest"
    cosign sign --yes "$image@$digest"
  done
fi
