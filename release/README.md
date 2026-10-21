# Releasing Yukta

Scripts and the runbook for building, packaging and publishing a Yukta release. The scripts work the
same on the release machine and in CI. The plan and the reasons behind it are in
[`design/plans/releases.md`](../design/plans/releases.md).

**Scope today (alpha):** Linux amd64 native server and CLI, `install.sh`, `.deb`/`.rpm`/`.tar.gz`, and a
`linux/amd64` image on GHCR. arm64, Windows, macOS and Docker Hub come later.

## Versioning policy

- [SemVer 2.0.0](https://semver.org). Git tag `vX.Y.Z[-prerelease]`.
- One source of truth: `version=` in `gradle.properties`. `ui/package.json` must match (the build checks it).
  The CLI (`yukta-cli --version`) and the server (`/actuator/info`) report it.
- Prerelease ladder: `0.1.0-alpha.N` → `0.1.0-beta.N` → `0.1.0-rc.N` → `0.1.0`. While the major version is
  `0`, a minor release may break things; a patch release only fixes bugs.
- `main` carries the next version with `-SNAPSHOT`. A release PR sets the exact version; the tag is cut from
  that merge commit.
- Image tags: the exact version always; `X.Y` and `latest` only for stable releases
  (`build-image.sh --promote` refuses prereleases).
- Published tags, images and release assets are never replaced. A bad release is superseded by the next one.
- GitHub's `releases/latest` skips prereleases, so until the first stable release the docs use pinned
  URLs (`.../releases/download/vX.Y.Z-alpha.N/install.sh`).
- Release notes: GitHub generates them, grouped by `.github/release.yml`; add a hand-written header
  (Highlights, Known limitations, Security fixes).

## Scripts

| Script | What it does | Runs on |
|---|---|---|
| `build-cli.sh` | `yukta-cli` for Linux, macOS and Windows, version taken from `gradle.properties` | any machine with Go |
| `build-server.sh` | Native `yukta` server for **this machine's** architecture, inside the pinned GraalVM image | amd64 machine for `linux-amd64` |
| `package.sh` | `.tar.gz`, `.deb`, `.rpm` for one platform | any machine with Go |
| `build-image.sh` | Runtime image, smoke test (health and version), optional push to the registries in `YUKTA_IMAGES` | machine with Docker |
| `sign.sh` | Keyless Sigstore signatures (`<file>.sigstore.json`) with `cosign` | machine with `cosign` |
| `install.sh`, `install.ps1` | One-command installers for users. `release.sh` attaches a copy of `install.sh` pinned to the release version. `install.ps1` is not attached until it has run on a real Windows machine | users' machines |
| `test-install.sh` | Tests `install.sh` against a local fake release server | any Linux/macOS machine |
| `release.sh` | All of the above for one version; dry run unless `--publish`; must run from the tag checkout | release machine |

`YUKTA_IMAGES` (space separated, default `ghcr.io/infenia/yukta`) lists the registries; set it to add
Docker Hub. `NATIVE_HEAP=24g` raises the native builder heap. `NATIVE_QUICK=1` builds fast for
rehearsals; `release.sh --publish` refuses it.

## One-time setup

1. Install Docker with buildx, Go, `gh` (logged in), `cosign` 2.x and `jq` on the release machine.
2. Log in to GHCR: `gh auth refresh -s write:packages`, then
   `gh auth token | docker login ghcr.io -u <github-user> --password-stdin`.
3. GitHub → Settings → Rules: add a tag ruleset for `v*` that blocks deletion and force-push.
   Settings → General → Releases: enable immutable releases.
4. Create a milestone named like the tag (`v0.1.0-alpha.1`). `released.yml` closes it on publish and
   comments on every issue the release fixed.

## Each release

1. Open a PR that sets the version in `gradle.properties` and `ui/package.json`; merge it; wait for CI.
2. Tag the merge commit: `git checkout main && git pull && git tag -s vX.Y.Z -m "Yukta X.Y.Z"`
   (`-a` if you have no signing key), then `git push origin vX.Y.Z`.
3. The **Release** workflow builds the CLI (five platforms) and the linux-amd64 server, smoke-tests and
   attests them, and opens a **draft** release (a prerelease when the version has a `-`).
4. Check out the tag: `git fetch --tags && git checkout vX.Y.Z`. Steps 4 to 6 run from it.
   Download the CI artifacts:
   ```bash
   gh run download <run-id> -n cli-binaries -D dist/cli
   gh run download <run-id> -n server-linux-amd64 -D dist/linux-amd64
   chmod +x dist/linux-amd64/yukta dist/cli/yukta-cli-*
   ```
   **If the server job failed** (out of memory, disk or time), build it here instead:
   `NATIVE_HEAP=24g release/build-server.sh` (records a PGO profile, then compiles; writes
   `dist/linux-amd64/yukta`). Say in the release notes that this binary was built and signed by a person,
   not attested by GitHub, and drop `--skip-server` below.
5. Dry run: `release/release.sh X.Y.Z --skip-server`. Packages, `SHA256SUMS`, builds and smoke-tests
   the image; pushes nothing.
6. Publish: `release/release.sh X.Y.Z --skip-server --publish`. Pushes the exact-version image, signs
   the files and the image (a browser sign-in opens), uploads everything to the draft.
7. First release only: GitHub → the `infenia` organisation → Packages → `yukta` → Package settings →
   make it **Public** and check it is linked to the repository.
8. Test the draft on a clean machine. Draft assets are not on the public download URL, so serve them locally:
   ```bash
   gh release download vX.Y.Z -D /tmp/rel/vX.Y.Z
   (cd /tmp/rel && python3 -m http.server 8765 &)
   docker run --rm --network host ubuntu:24.04 bash -c \
     'apt-get update -qq && apt-get install -y -qq curl >/dev/null &&
      curl -fsSL http://127.0.0.1:8765/vX.Y.Z/install.sh | YUKTA_DOWNLOAD_BASE=http://127.0.0.1:8765 sh &&
      ~/.local/bin/yukta-cli --version'
   docker run --rm -p 127.0.0.1:8080:8080 -e YUKTA_API_KEY=x ghcr.io/infenia/yukta:X.Y.Z
   ```
   Then check `/actuator/health` is UP, `/actuator/info` shows the version, and run the Quickstart workflow.
   Do this before publishing: releases are immutable afterwards.
9. Edit the draft's notes, then **Publish release**. For a prerelease, keep it marked as one.
10. Merge the docs PR for this release; check the Mintlify deployment is live.
11. Bump `main` to the next `-SNAPSHOT`.
12. Stable releases only: `release/build-image.sh X.Y.Z --promote` moves `X.Y` and `latest`.

## Verify a download (for users)

```bash
sha256sum --check --ignore-missing SHA256SUMS
cosign verify-blob --bundle FILE.sigstore.json FILE \
  --certificate-identity-regexp '.*' --certificate-oidc-issuer-regexp '.*'   # tighten to the signer
gh attestation verify yukta-cli-linux-amd64 -R infenia/yukta               # CI-built files
```

`dist/` is not committed.
