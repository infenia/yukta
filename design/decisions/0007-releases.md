# ADR 0007: Release and install channels

- **Status:** accepted. The first alpha (`0.1.0-alpha.1`) is scoped to linux/amd64 and GHCR. arm64, Docker Hub, Windows and macOS follow in later phases. The runbook is [`release/README.md`](../../release/README.md).
- **Date:** 2026-10-22 (condensed from the releases plan into this ADR on 2026-11-22)
- **Decided by:** Arun.
- **Epic:** the Yukta Roadmap epic `releases plan`.

## Context

A person should be able to run Yukta without a JDK or a build. Facts that shape the choice:

- GraalVM native-image cannot cross-compile: each OS and architecture needs a host of its own.
- The native build needs about 16 GB of builder heap and has never been measured on hosted runners.
- Go cross-compiles the CLI for all five platforms from one Linux machine.
- Server and CLI both used the name `yukta`.
- A multi-architecture image needs one native binary per architecture.
- macOS and Windows signing need paid accounts.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | Hardware | None for arm64 or macOS. Use GitHub-hosted runners (`ubuntu-24.04-arm`, `windows-latest`, `macos-latest`) and a bundled-runtime build (`jlink` plus the boot jar) so Windows and macOS users are not blocked on native builds. |
| D2 | Binary names | Server `yukta`, CLI `yukta-cli`. |
| D3 | Build strategy | Every artifact comes from a script in `release/` that behaves the same on any machine. Build anywhere, **assemble, sign and publish from one place** (`release/release.sh vX.Y.Z`, dry run by default, `--publish` to publish). A failed runner delays one artifact and never corrupts a release. |
| D4 | Linux and Docker first | Docker image `linux/amd64` and `linux/arm64` under one tag, then Linux archives and `.deb` / `.rpm` (built with `nfpm` in Docker). |
| D5 | Windows and macOS | Tiered: Docker Desktop, then bundled-runtime archives and package-manager manifests (Homebrew, Scoop, winget), then native builds on hosted runners if they fit. `.msi` built in Docker with `wixl`; `.pkg` possible. Unsigned at first, and the docs say so. |
| D6 | Registries | GHCR and Docker Hub with identical tags and digests. |
| D7 | Tags | `X.Y.Z`, `X.Y`, and `latest` only for stable releases; `-rc.N` never moves `latest`. Published tags are never overwritten. |
| D8 | Supply chain | Keyless `cosign` signatures (Sigstore), CycloneDX SBOM, build provenance where built in CI, a Trivy scan that fails on unignored high findings, a smoke test per architecture before the manifest is published. |
| D9 | Docker build | Split build from packaging: a runtime target copies a prebuilt binary by `TARGETARCH`, so the image and the archive contain the same binary. No UPX in releases unless measured to help. |
| D10 | Installers | `install.sh` and `install.ps1` attached to every release, pinned to its version, verifying `SHA256SUMS`, installing without `sudo`. |
| D11 | Rollback | A draft release can be deleted; a bad published release is superseded by the next patch. |

## Consequences

- Locally built artifacts have no GitHub provenance; they are signed by a person and the docs say which is which.
- Windows and macOS users see Gatekeeper or SmartScreen warnings until signing certificates exist (open: Apple Developer ID, Windows certificate).
- Open: the Docker Hub namespace (assumed `infenia/yukta`), measured native build time and memory per platform, and the first end-to-end rehearsal `-rc`.
- Docs lead with Docker and downloads, with "run from source with a JDK" last.
