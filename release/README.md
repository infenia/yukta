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
- `main` carries the next planned release with `-SNAPSHOT` (after `0.1.0-alpha.2`: `0.1.0-alpha.3-SNAPSHOT`;
  after the last alpha, the first beta, and so on). A release PR sets the exact version; the tag is cut from
  that merge commit. Do not use `0.1.0-SNAPSHOT` while alphas and betas are still coming: it reads as the
  final release and sorts before every `0.1.0-alpha.N`.
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
| `sign.sh` | One keyless Sigstore signature on `SHA256SUMS` (`SHA256SUMS.sigstore.json`), plus the image, with `cosign` | machine with `cosign` |
| `smoke-test.sh` | Against a running server: health, then the Quickstart workflow must succeed. Used by CI and by `build-image.sh` | any machine with `curl` |
| `install.sh`, `install.ps1` | One-command installers for users. `release.sh` attaches a copy of `install.sh` pinned to the release version. `install.ps1` is not attached until it has run on a real Windows machine | users' machines |
| `test-install.sh` | Tests `install.sh` against a local fake release server | any Linux/macOS machine |
| `release.sh` | All of the above for one version; dry run unless `--publish`; must run from the tag checkout | release machine |

`YUKTA_IMAGES` (space separated, default `ghcr.io/infenia/yukta`) lists the registries; set it to add
Docker Hub. `NATIVE_HEAP=24g` raises the native builder heap. `NATIVE_QUICK=1` builds fast for
rehearsals; `release.sh --publish` refuses it.

## One-time setup

Each item says why it is needed, so you can tell when a step may be skipped or has to be redone.

1. **Tools on the release machine:** Docker with buildx, Go, `gh` (logged in), `cosign` 2.x, `jq`.
   *Why:* Docker builds and runs the image, Go builds the packages (`nfpm`) and the CLI, `gh` downloads
   the CI artifacts and uploads to the release, `cosign` signs.
2. **Log in to GHCR:** `gh auth refresh -s write:packages`, then
   `gh auth token | docker login ghcr.io -u <github-user> --password-stdin`.
   *Why:* the image is pushed to `ghcr.io/infenia/yukta` from this machine, and the default GitHub token
   cannot write packages. Redo it if `docker push` says "denied" or the token expires.
3. **Protect release tags:** GitHub → Settings → Rules → a tag ruleset for `v*` that blocks deletion and
   force-push. Settings → General → Releases → enable immutable releases.
   *Why:* people pin versions and verify them. A tag moved or a published release edited afterwards
   breaks that trust, so GitHub should refuse it. The price: a wrong tag stays forever, so you ship the
   next version instead (this happened: `v0.1.0-alpha.1` was never released).
4. **Allow public packages in the organisation:** organisation → Settings → Packages → Package creation →
   tick Public. *Why:* a first push creates a private package, and the organisation may forbid
   making it public. Without this, nobody can `docker pull` the image.
5. **Create a milestone named like the tag** (`v0.1.0-alpha.2`). *Why:* when you publish the release,
   `released.yml` comments on every issue it fixed and closes the milestone. No milestone, no comments.

## Each release

### Prepare

1. **Open a PR that sets the version** in `gradle.properties` and `ui/package.json`; merge it; wait for CI.
   *Why:* `gradle.properties` is the one version; the CLI, `/actuator/info`, the packages and the
   archive names all derive from it. The build fails if `ui/package.json` differs, and the release
   workflow fails if the tag does not match. A merged PR gives the version change a review and a CI run.
2. **Merge fixes before tagging.** *Why:* the tag cannot be deleted (setup step 3). Every problem found
   after tagging costs a version number.

### Build (mostly automatic)

3. **Tag the merge commit and push it:**
   `git checkout main && git pull && git tag -s vX.Y.Z -m "Yukta X.Y.Z" && git push origin vX.Y.Z`
   (`-a` instead of `-s` if you have no signing key).
   *Why:* the tag is what the world sees as the release; pushing it starts the **Release** workflow. A
   signed tag shows "Verified" on GitHub, so people know it came from you.
4. **The workflow builds** the CLI (five platforms) and the linux-amd64 server, runs the Quickstart
   workflow against the server binary, attests both, and opens a **draft** release.
   *Why GitHub builds the binaries:* attestation ties each file to this repository, this commit and this
   workflow run (`gh attestation verify`), which a binary built on a laptop cannot offer. *Why the
   Quickstart smoke test:* a health check passed on a native image where every workflow expression failed
   (alpha.1). *Why a draft:* nothing is public yet; you test it first.
   The server job takes about 45 minutes. A "PGO profile" out-of-memory message in its log is expected
   on the hosted runner: the build falls back to no profile and carries on. Only a red job is a failure.

### Assemble and test (on your machine)

5. **Check out the tag:** `git fetch --tags && git checkout vX.Y.Z`.
   *Why:* `release.sh` refuses to run anywhere else. The packages and the image's revision label must
   come from exactly the tagged commit, not from whatever `main` has become.
6. **Download the CI artifacts:**
   ```bash
   gh run download <run-id> -n cli-binaries -D dist/cli
   gh run download <run-id> -n server-linux-amd64 -D dist/linux-amd64
   chmod +x dist/linux-amd64/yukta dist/cli/yukta-cli-*
   ```
   *Why:* the packages, archive and image are made from the exact binaries CI built and attested.
   Rebuilding them here would give different bytes, and the attestation would no longer match.
   **If the server job failed** (out of memory, disk, time): `NATIVE_HEAP=24g release/build-server.sh`
   builds it here (PGO profile, then compile; writes `dist/linux-amd64/yukta`). Say in the release notes
   that this binary was built and signed by a person, not attested by GitHub, and drop `--skip-server`
   in the next two steps.
7. **Dry run:** `release/release.sh X.Y.Z --skip-server`.
   *Why:* it does everything except the irreversible parts (push, sign, upload): packages, `SHA256SUMS`,
   the image, and the smoke test against the image. Fix problems here, while they cost nothing.

### Publish to the draft

8. **Publish:** `release/release.sh X.Y.Z --skip-server --publish`. It pushes
   `ghcr.io/infenia/yukta:X.Y.Z`, signs the checksums file and the image, and uploads everything to
   the draft.
   *Why signing:* a checksum only proves a file matches the list; the signature proves the list came
   from you (your identity is recorded in a public log, with no key to store or leak). *Why only
   `SHA256SUMS`:* it lists the hash of every other file, and each signature costs a browser sign-in.
   **Run it in a real terminal, not a tool that captures output.** Cosign prints a one-time code and
   waits 5 minutes for the browser; if the code expires the sign-in fails. The image is pushed *before*
   signing, so after a failure do not rerun everything: sign and upload by hand with
   `cosign sign-blob --yes --bundle dist/assets/SHA256SUMS.sigstore.json dist/assets/SHA256SUMS`,
   `cosign sign --yes ghcr.io/infenia/yukta@<index digest>` and
   `gh release upload vX.Y.Z dist/assets/* --clobber`.
9. **First release only: make the package public** (organisation → Packages → `yukta` → Package settings
   → Change visibility → Public). *Why:* new packages are private; without this, users and the
   logged-out `docker pull` test get "denied".
10. **Test the draft on a clean machine.** *Why:* it is the only check that uses what users will really
    download, and a published release cannot be fixed (setup step 3). Draft assets are not on the public
    URL, so serve them locally:
    ```bash
    gh release download vX.Y.Z -D /tmp/rel/vX.Y.Z
    (cd /tmp/rel/vX.Y.Z && sha256sum -c SHA256SUMS && \
     cosign verify-blob --bundle SHA256SUMS.sigstore.json SHA256SUMS \
       --certificate-identity <you> --certificate-oidc-issuer https://github.com/login/oauth)
    (cd /tmp/rel && python3 -m http.server 8765 &)
    docker run --rm --network host ubuntu:24.04 bash -c \
      'apt-get update -qq && apt-get install -y -qq curl >/dev/null &&
       curl -fsSL http://127.0.0.1:8765/vX.Y.Z/install.sh | YUKTA_DOWNLOAD_BASE=http://127.0.0.1:8765 sh &&
       ~/.local/bin/yukta-cli --version'
    docker run -d --rm --name ytest -p 127.0.0.1:18081:8080 -e YUKTA_API_KEY=x ghcr.io/infenia/yukta:X.Y.Z
    release/smoke-test.sh http://127.0.0.1:18081 x ; docker rm -f ytest
    ```
    Also pull the image with `DOCKER_CONFIG=$(mktemp -d) docker pull ghcr.io/infenia/yukta:X.Y.Z` (no
    login: proves it is public) and install the `.deb` in an Ubuntu container. Check that **every file
    name in `SHA256SUMS` exists in the draft** (GitHub renames some characters, such as `~` to `.`).

### Release

11. **Edit the draft's notes, then Publish release.** Keep "pre-release" ticked for a version with a `-`.
    *Why notes:* GitHub lists the merged PRs; you add what users need to know (highlights, known
    limitations, who signed it). OpenSSF Best Practices also requires release notes. Publishing is the
    point of no return: from here the assets and tag cannot change.
12. **Merge the docs PR for this release,** then check the Mintlify deployment is live.
    *Why last:* Mintlify publishes `docs/` from `main` immediately; merged earlier, the install
    commands would point at a release that does not exist yet.
13. **Bump `main` to the next `-SNAPSHOT`** in `gradle.properties` and `ui/package.json`.
    *Why:* so builds from `main` are not mistaken for the released version.
14. **Stable releases only:** `release/build-image.sh X.Y.Z --promote` moves `X.Y` and `latest`.
    *Why separate:* these tags move, so they point at a release only after it is published and tested.
    Prereleases never move them.

### If something goes wrong

| Problem | What to do |
|---|---|
| Found a bug after tagging, before publishing | Do not publish. Delete the draft (`gh release delete vX.Y.Z`), fix on `main`, and release the next version. The tag stays; that is fine |
| Cosign sign-in expired | Rerun the signing commands by hand (step 8) in a real terminal |
| `docker push` denied | Redo setup step 2; for the first push make sure you can create packages in the organisation |
| Package cannot be made public | Setup step 4 |
| A file name in `SHA256SUMS` differs from the draft | Fix the name in `dist/assets/SHA256SUMS`, sign it again, upload both files with `--clobber` |

## Verify a download (for users)

```bash
sha256sum --check --ignore-missing SHA256SUMS
cosign verify-blob --bundle SHA256SUMS.sigstore.json SHA256SUMS \
  --certificate-identity-regexp '.*' --certificate-oidc-issuer-regexp '.*'   # tighten to the signer
gh attestation verify yukta-cli-linux-amd64 -R infenia/yukta               # CI-built files
```

`dist/` is not committed.
