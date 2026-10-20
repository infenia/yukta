# Release scripts

Scripts that build, package and publish a Yukta release. They work the same on the release machine
and in CI. The plan and the reasons behind it are in
[`docs/internal/plans/releases.mdx`](../docs/internal/plans/releases.mdx).

| Script | What it does | Runs on |
|---|---|---|
| `build-cli.sh` | `yukta-cli` for Linux, macOS and Windows | any machine with Go |
| `build-server.sh` | Native `yukta` server for **this machine's** architecture, inside the pinned GraalVM image | amd64 machine for `linux-amd64`, arm64 machine for `linux-arm64` |
| `package.sh` | `.tar.gz`, `.deb`, `.rpm` for one platform | any machine with Go |
| `build-image.sh` | Runtime image per architecture, smoke test, optional multi-arch push to GHCR and Docker Hub | machine with Docker |
| `sign.sh` | Keyless Sigstore signatures with `cosign` | machine with `cosign` |
| `release.sh` | All of the above for one version; dry run unless `--publish` | release machine |

## Release steps

1. Set the version in `gradle.properties` and `ui/package.json` (they must match), merge to `main`, wait for CI.
2. Push the tag `vX.Y.Z`. The `Release` workflow builds the CLI and the **arm64 server** and opens a *draft* GitHub release.
3. On the release machine, download the workflow artifacts `server-linux-arm64` into `dist/linux-arm64/` and `cli-binaries` into `dist/cli/`. A published release uses the CLI binaries CI built and attested, and refuses to publish without them or without both server binaries.
4. Dry run: `release/release.sh X.Y.Z` (builds the amd64 server, packages, builds and smoke-tests both images, pushes nothing).
5. `release/release.sh X.Y.Z --publish` (pushes the exact-version images, signs, uploads to the draft).
6. Install from the published artifacts on a clean machine, then publish the draft release.
7. `release/build-image.sh X.Y.Z --promote` moves `X.Y` and `latest` (stable releases only).

For a rehearsal, `NATIVE_QUICK=1 release/build-server.sh` compiles with `-Ob`: much faster, slower at run time. `release.sh --publish` refuses it. `NATIVE_HEAP=24g` raises the builder heap on a machine with the memory (or swap) for it.

Run `docker run --privileged --rm tonistiigi/binfmt --install all` once on the release machine so the arm64 image can be smoke tested under QEMU.

`dist/` is not committed.
