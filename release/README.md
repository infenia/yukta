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
- `main` carries the last released version. The release PR that release-please opens sets the next one;
  the tag is created when it merges.
- Image tags: the exact version always; `X.Y` and `latest` only for stable releases
  (`build-image.sh --promote` refuses prereleases).
- Published tags, images and release assets are never replaced. A bad release is superseded by the next one.
- GitHub's `releases/latest` skips prereleases, so until the first stable release the docs use pinned
  URLs (`.../releases/download/vX.Y.Z-alpha.N/install.sh`).
- Release notes: release-please writes them (and `CHANGELOG.md`) from Conventional Commits; add a
  hand-written header (Highlights, Known limitations, Security fixes) before publishing.

## Scripts

| Script | What it does | Runs on |
|---|---|---|
| `build-cli.sh` | `yukta-cli` for Linux, macOS and Windows, version taken from `gradle.properties` | any machine with Go |
| `build-server.sh` | Native `yukta` server for **this machine's** architecture, inside the pinned GraalVM image | amd64 machine for `linux-amd64` |
| `package.sh` | `.tar.gz`, `.deb`, `.rpm` for one platform | any machine with Go |
| `build-image.sh` | Runtime image, smoke test (health and version), optional push to the registries in `YUKTA_IMAGES` | machine with Docker |
| `sign.sh` | One keyless Sigstore signature on `SHA256SUMS` (`SHA256SUMS.sigstore.json`), plus the image, with `cosign` | CI, or a machine with `cosign` |
| `smoke-test.sh` | Against a running server: health, then the Quickstart workflow must succeed. Used by CI and by `build-image.sh` | any machine with `curl` |
| `install.sh`, `install.ps1` | One-command installers for users. `release.sh` attaches a copy of `install.sh` pinned to the release version. `install.ps1` is not attached until it has run on a real Windows machine | users' machines |
| `test-install.sh` | Tests `install.sh` against a local fake release server | any Linux/macOS machine |
| `release.sh` | Package, image, checksums, signing and upload for one version; dry run unless `--publish`; must run from the tag checkout | the `assemble` job, or the release machine |

`YUKTA_IMAGES` (space separated, default `ghcr.io/infenia/yukta`) lists the registries; set it to add
Docker Hub. `NATIVE_HEAP=24g` raises the native builder heap. `NATIVE_QUICK=1` builds fast for
rehearsals; `release.sh --publish` refuses it.

## One-time setup

1. **GitHub App for bots.** The `YUKTA_PROJECT_APP` App needs Contents and Pull requests read/write and
   must be installed on `infenia/yukta` (variable `YUKTA_PROJECT_APP_CLIENT_ID`, secret
   `YUKTA_PROJECT_APP_KEY`). *Why:* release-please and the docs bump open pull requests and push a tag.
   Anything made with the default `GITHUB_TOKEN` starts no workflows, so the release PR would never get
   its required checks and the tag would build nothing.
2. **Allow auto-merge** (Settings → General). *Why:* the docs pull request merges itself once approved.
3. **GHCR write access for the workflow.** organisation → Packages → `yukta` → Package settings →
   Manage Actions access → add `infenia/yukta` with Write. *Why:* the first image was pushed from a
   person's machine, so the package does not yet grant the workflow access.
4. **Protect release tags:** a tag ruleset for `v*` that blocks deletion and force-push, and
   Settings → General → Releases → enable immutable releases. *Why:* people pin versions and verify
   them. The price: a wrong tag stays forever, so you ship the next version instead.
5. **Allow public packages in the organisation** (Settings → Packages → Package creation → Public), and
   make the `yukta` package public after its first push. *Why:* nobody can `docker pull` a private one.
6. **Create a milestone named like the tag** (`v0.1.0-alpha.3`). *Why:* on publish, `released.yml`
   comments on every issue the release fixed and closes the milestone.

## Each release

One pull request to approve, one button to press.

1. **Merge the release PR.** release-please keeps a PR titled `chore(main): release X.Y.Z` up to date
   from the Conventional Commits on `main`. It sets the version in `gradle.properties` and
   `ui/package.json` and writes `CHANGELOG.md`. Approve and merge it when you want to release.
   *Why not by hand:* the version, changelog and tag always agree, and the PR gets the same review and
   CI as any change. Merge fixes first: a tag cannot be deleted, so every problem found after tagging
   costs a version number.
   - A prerelease advances by itself (`alpha.2` → `alpha.3`). To change the stage or the version, add
     a `Release-As: 0.1.0-beta.1` footer to a commit on `main`.
2. **CI does the rest** (the **Release** workflow, started by the tag; about an hour):
   - builds the CLI (five platforms) and the linux-amd64 server, runs the Quickstart workflow against the
     server binary, and attests both (`gh attestation verify`);
   - `assemble` runs `release/release.sh --publish`: packages, `SHA256SUMS`, the image pushed to GHCR,
     and signatures on the checksums and the image. Signing is keyless: cosign signs as the workflow, so
     there is no browser sign-in and no key to store;
   - uploads everything to the **draft** release release-please created;
   - `verify` downloads the draft, checks the checksums and signature, installs it with `install.sh` and
     the `.deb` on a clean Ubuntu container, pulls the image logged out and runs the Quickstart on it.
   A "PGO profile" out-of-memory message in the server job's log is expected on the hosted runner; only
   a red job is a failure.
3. **Publish.** When the workflow is green, open the draft, add highlights and known limitations to the
   notes, keep "pre-release" ticked for a version with a `-`, and press Publish. *Why a person:* this is
   the point of no return; from here the assets and tag cannot change. Release notes are also an
   OpenSSF Best Practices requirement.
4. **Approve the docs PR.** Publishing opens `chore(docs): install the X.Y.Z release`, which points the install
   commands at the new release and has auto-merge on. Approve it, then check the Mintlify deployment is
   live. *Why after publishing:* Mintlify publishes `docs/` from `main` immediately, so merged earlier,
   the install commands would point at a release that does not exist yet.
5. **Stable releases only:** `release/build-image.sh X.Y.Z --promote` moves `X.Y` and `latest`.
   *Why separate:* these tags move, so they point at a release only after it is published and tested.

`main` carries the last released version; there is no `-SNAPSHOT`. A build from `main` is identified by
its git SHA.

Tags are lightweight tags made by the release-please App, not signed by a person. Trust comes from the
build attestations and the Sigstore signature of the workflow, which anyone can verify (below).

### Releasing from your own machine (fallback)

Use it when the hosted server build fails (out of memory, disk, time). Tools: Docker with buildx, Go, `gh`
(logged in), `cosign` 2.x, `jq`; `gh auth token | docker login ghcr.io -u <user> --password-stdin`.

```bash
git fetch --tags && git checkout vX.Y.Z
gh run download <run-id> -n cli-binaries -D dist/cli          # CI-built, attested CLI
NATIVE_HEAP=24g release/build-server.sh                       # server, built here
release/release.sh X.Y.Z                                      # dry run: nothing pushed, signed or uploaded
release/release.sh X.Y.Z --publish                            # in a real terminal: cosign asks for a browser sign-in
```

Say in the release notes that this server binary was built and signed by a person, not attested by GitHub.
The image is pushed before signing. After a failed sign-in you can rerun (pushed tags are kept), or run
`cosign sign-blob --yes --bundle dist/assets/SHA256SUMS.sigstore.json dist/assets/SHA256SUMS`,
`cosign sign --yes ghcr.io/infenia/yukta@<index digest>` and `gh release upload vX.Y.Z dist/assets/* --clobber`.
Signatures made this way carry your identity, not the workflow's; name it in the release notes.

### If something goes wrong

| Problem | What to do |
|---|---|
| Found a bug after tagging, before publishing | Do not publish. Delete the draft (`gh release delete vX.Y.Z`), fix on `main`, and merge the next release PR. The tag stays; that is fine |
| `assemble` fails at `docker push` | Setup step 3 |
| `assemble` fails before the image push | Fix the cause and rerun the job |
| `assemble` fails after the image push (signing, upload) | Rerun it. An image tag already in a registry is kept, never replaced, so the digest that gets signed is the one that was pushed. Or sign and upload by hand as in the fallback above |
| `verify` fails | Read the log. The assets are intact, so rerunning `verify` alone is safe |
| Package cannot be made public | Setup step 5 |
| Release PR has no CI checks | The App token is missing or not installed (setup step 1) |

## Verify a download (for users)

```bash
sha256sum --check --ignore-missing SHA256SUMS
cosign verify-blob --bundle SHA256SUMS.sigstore.json SHA256SUMS \
  --certificate-identity-regexp '^https://github.com/infenia/yukta/.github/workflows/release.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify yukta-cli-linux-amd64 -R infenia/yukta               # CI-built files
```

`dist/` is not committed.
