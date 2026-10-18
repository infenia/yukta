#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Run a release from the release machine. Builds what this machine can build, takes the rest from
# dist/ (for example the arm64 binary downloaded from the GitHub Actions run), then packages,
# builds images and checksums. Publishes only with --publish.
#
# Usage: release/release.sh <version> [--publish] [--skip-server] [--allow-branch]
#   <version>       0.1.0 or 0.1.0-rc.1 (a leading v is fine)
#   --publish       push images, sign everything, upload to the GitHub release as a draft
#   --skip-server   do not run the native build; use the binaries already in dist/
#   --allow-branch  allow a release from a branch other than main (for rehearsals)
#
# Before this: set the version in gradle.properties and ui/package.json, merge to main, wait for CI.
# The arm64 binary comes from the release workflow run for the tag (artifact server-linux-arm64).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 ]] || die "usage: release.sh <version> [--publish] [--skip-server] [--allow-branch]"
version="$(normalize_version "$1")"; shift
publish=false; skip_server=false; allow_branch=false
for arg in "$@"; do
  case "$arg" in
    --publish) publish=true ;;
    --skip-server) skip_server=true ;;
    --allow-branch) allow_branch=true ;;
    *) die "unknown option $arg" ;;
  esac
done

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "version '$version' is not semantic (X.Y.Z or X.Y.Z-rc.N)"

log "preflight"
cd "$REPO_ROOT"
[[ -z "$(git status --porcelain)" ]] || die "working tree is not clean"
branch="$(git rev-parse --abbrev-ref HEAD)"
$allow_branch || [[ "$branch" == "main" ]] || die "on branch '$branch'; release from main or pass --allow-branch"
[[ "$(gradle_version)" == "$version" ]] || die "gradle.properties has version '$(gradle_version)', not '$version'"
ui_version="$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' ui/package.json | head -1)"
[[ "$ui_version" == "$version" ]] || die "ui/package.json has version '$ui_version', not '$version'"

rm -rf "$DIST/cli" "$DIST/packages"
"$RELEASE_DIR/build-cli.sh"
if ! $skip_server; then "$RELEASE_DIR/build-server.sh"; fi

for platform in "${PLATFORMS[@]}"; do
  if [[ -x "$DIST/$platform/yukta" ]]; then
    "$RELEASE_DIR/package.sh" "$version" "$platform"
  else
    log "WARNING: no server binary for $platform; it is left out of this release"
  fi
done

# One flat folder holds every file the release publishes, so one SHA256SUMS covers them all and
# `sha256sum --check` works on a plain download folder.
assets="$DIST/assets"
rm -rf "$assets"
mkdir -p "$assets"
cp "$DIST"/packages/* "$DIST"/cli/yukta-cli-* "$assets/"
# The install scripts attached to a release install exactly that release.
sed "s/^DEFAULT_VERSION=\"@VERSION@\"/DEFAULT_VERSION=\"$version\"/" "$RELEASE_DIR/install.sh" > "$assets/install.sh"
sed "s/^\( *\)\$defaultVersion = '@VERSION@'/\1\$defaultVersion = '$version'/" "$RELEASE_DIR/install.ps1" > "$assets/install.ps1"
chmod 0755 "$assets/install.sh"
grep -q "DEFAULT_VERSION=\"$version\"" "$assets/install.sh" || die "could not pin the version in install.sh"
grep -q "defaultVersion = '$version'" "$assets/install.ps1" || die "could not pin the version in install.ps1"
(cd "$assets" && sha256sum -- * > SHA256SUMS.tmp && mv SHA256SUMS.tmp SHA256SUMS)
if $publish; then
  "$RELEASE_DIR/build-image.sh" "$version" --push
  "$RELEASE_DIR/sign.sh" "$version" --images
  need gh
  notes_flag=(); is_prerelease "$version" && notes_flag=(--prerelease)
  gh release view "v$version" >/dev/null 2>&1 || gh release create "v$version" --draft "${notes_flag[@]}" --generate-notes --title "v$version"
  gh release upload "v$version" "$assets"/* --clobber
  log "draft release v$version uploaded. Install from the published artifacts on a clean machine, then publish it."
else
  "$RELEASE_DIR/build-image.sh" "$version"
  log "dry run complete: nothing was pushed, signed or uploaded. Release files are in $assets"
fi
