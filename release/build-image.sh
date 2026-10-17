#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Build the runtime image for each platform that has a server binary in dist/, smoke test it, and
# optionally push one multi-architecture manifest to GHCR and Docker Hub.
#
# Usage: release/build-image.sh <version> [--push]
#   without --push: builds and tests each platform locally (an image for another architecture runs
#   under QEMU; install it once with: docker run --privileged --rm tonistiigi/binfmt --install all)
#   with --push: pushes per-architecture tags, then the manifest, to both registries; run
#   'docker login ghcr.io' and 'docker login' first
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 ]] || die "usage: build-image.sh <version> [--push]"
version="$(normalize_version "$1")"
push=false
[[ "${2:-}" == "--push" ]] && push=true
need docker
revision="$(git -C "$REPO_ROOT" rev-parse HEAD)"

# Start the image, wait for /actuator/health, check it says UP. The image has no shell or curl,
# so the check runs from the host against a published port.
smoke_test() {
  local image="$1" platform="$2" port=18080 name="yukta-smoke-$$"
  docker run -d --rm --name "$name" --platform "linux/$platform" -p "127.0.0.1:$port:8080" \
    -e YUKTA_API_KEY=smoke-test-key "$image" >/dev/null
  trap 'docker rm -f "$name" >/dev/null 2>&1 || true' RETURN
  for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      log "smoke test passed for $platform"
      return 0
    fi
    sleep 2
  done
  docker logs "$name" >&2 || true
  die "smoke test failed for $platform: /actuator/health never reported UP"
}

built=()
for platform in "${PLATFORMS[@]}"; do
  arch="$(arch_of "$platform")"
  if [[ ! -x "$DIST/$platform/yukta" ]]; then
    log "skipping $platform: no server binary in $DIST/$platform"
    continue
  fi
  # The Dockerfile reads dist/ under the repository root; link the chosen DIST there if elsewhere.
  [[ "$DIST" == "$REPO_ROOT/dist" ]] || die "build-image.sh needs DIST=$REPO_ROOT/dist (the Docker build context)"
  tag="$IMAGE_GHCR:$version-$arch"
  log "building $tag"
  docker buildx build --platform "linux/$arch" -f "$RELEASE_DIR/Dockerfile" \
    --build-arg "VERSION=$version" --build-arg "REVISION=$revision" \
    --load -t "$tag" "$REPO_ROOT"
  smoke_test "$tag" "$arch"
  built+=("$arch")
done
[[ ${#built[@]} -gt 0 ]] || die "no platform had a server binary"
$push || { log "built and tested: ${built[*]} (not pushed)"; exit 0; }

# Docker Hub gets the same per-architecture images and the same manifest as GHCR.
tags=("$version")
if ! is_prerelease "$version"; then
  major_minor="${version%.*}"
  tags+=("$major_minor" latest)
fi
for image in "$IMAGE_GHCR" "$IMAGE_HUB"; do
  sources=()
  for arch in "${built[@]}"; do
    docker tag "$IMAGE_GHCR:$version-$arch" "$image:$version-$arch"
    docker push "$image:$version-$arch"
    sources+=("$image:$version-$arch")
  done
  args=()
  for t in "${tags[@]}"; do args+=(-t "$image:$t"); done
  docker buildx imagetools create "${args[@]}" "${sources[@]}"
  docker buildx imagetools inspect "$image:$version"
done
