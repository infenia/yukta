# ADR 0005: Editions: Community, Plus and Enterprise

- **Status:** accepted. Built in this repository (hooks, `plus` module, feature gate, edition endpoint, CLA drafts). The private Enterprise repository, publishing and the Enterprise features are not started.
- **Date:** 2026-10-21 (condensed from the editions plan into this ADR on 2026-11-22)
- **Decided by:** Arun. D2, D6 to D9 and D11 are still open.
- **Epic:** the Yukta Roadmap epic `editions plan`. Licensing text: [`LICENSING.md`](../../LICENSING.md), [`docs/reference/licensing.mdx`](../../docs/reference/licensing.mdx).

## Context

Yukta should be a freemium product: an open server anyone can run, a tier that stays open source but funds the project, and a closed tier for enterprise needs. The code was already split along these lines: `persistence-postgres` and `workers` are separate modules that only depend on `core` and `plugin-api`, the storage and execution seams are interfaces owned by `core`, defaults are `@ConditionalOnMissingBean` beans, and add-on modules register through `AutoConfiguration.imports`. No artifact has ever been published.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | Tier-2 licence | **AGPL-3.0-only** with a commercial licence for those who cannot use AGPL. `-only`, so future AGPL versions cannot change what users owe. The AGPL covers `plus/` only. |
| D2 | Tier-2 name | Open. "Plus" is a working name. |
| D3 | Single-node file persistence | **Plus.** Community refuses it with a clear start-up message and keeps state in memory. |
| D4 | Named keys, roles, hashed and session-limited keys | **Enterprise.** Still in the public build behind the `ApiAuthenticator` hook; cut when the private repository can receive the code. |
| D5 | AI features (`LLM_CALL`, MCP tools, `SESSION_MEMORY`, decision plugins, supervisors) | **Stay in Community.** |
| D6 | Enterprise packaging | Open. Recommended: compose at build time in the private repository (own `boot-enterprise` module, own native binary and image), because a native image cannot load jars at run time. |
| D7 | Enterprise distribution | Open. Recommended: private registry with per-customer credentials. |
| D8 | Maven coordinates | Open. Recommended: Maven Central under `com.infenia.yukta`. |
| D9 | Timing against v0.1.0 | Open. Recommended: contracts and the Community cut before tagging, publishing straight after. |
| D10 | Contributor agreement | Adapted Apache CLA (individual and corporate) with a GitHub CLA bot. Drafts, pending legal review. |
| D11 | Trademark | Open. Recommended: a short policy for "Yukta" and the logo. |
| D12 | Home of the extension contract | **Hooks stay in `core` (Apache)**; the integration layer (`EnterpriseModule`, `PlusApi.VERSION`, registry, edition reporting) and contract tests live in `plus` (AGPL). No separate `spi` module. |
| D13 | `persistence-postgres` and `workers` | **In `plus/` for now**; they move to the private repository when it exists. |

### What each tier holds

- **Community (Apache-2.0):** engine, REST/MCP/UI/CLI, built-in plugins, AI features, supervisors, in-memory state, one API key, safety guards, scheduler limits.
- **Plus (AGPL-3.0-only):** file stores, PostgreSQL stores and leader election, remote workers, the integration layer.
- **Enterprise (proprietary, separate repository `infenia/yukta-enterprise`):** named keys and roles, then new work: SSO, secret managers, encryption at rest, audit export, multi-tenancy, licence verification. PostgreSQL, HA and workers move here later.

### Mechanism

- Extensions provide a bean of a hook type and declare `@AutoConfiguration(before = DefaultsAutoConfiguration.class)`; defaults step aside.
- `EditionInfo` (`COMMUNITY`, `PLUS`, `ENTERPRISE`) is served by `GET /api/edition` and `/actuator/info`.
- `FeatureGateEnvironmentPostProcessor` refuses a setting whose module is not in the build and names the build switch. Gates refuse to start a feature; they never delete or hide data.
- The Plus build is a separate artifact from the Community build; Community must never depend on Plus. A CI guard checks it.
- Enterprise licence: a signed file (for example Ed25519 JWT), verified offline, no phone-home, grace period on expiry, then Enterprise features refuse to start. Data is never deleted or locked.

## Consequences

- The Apache code already in git history stays Apache. The AGPL protects only new work; the moat is Enterprise.
- Community loses on-disk persistence. Mitigations: `yukta.bootstrap.dir` (apply session files at start-up), export through the REST API, an in-memory notice at start-up.
- Moving the two modules later is the copyright owner relicensing its own code; history is not rewritten.
- Third-party extensions are possible through the Apache hooks without `plus`; how far the AGPL reaches into them is contested and needs a lawyer.

## Open items

Ownership of the copyright, CLA review, trademark policy, the Enterprise EULA, the Plus commercial licence and a short fixed-fee review by an open-source lawyer gate any outside contribution and any commercial use. Publishing artifacts and creating the private repository are the next engineering steps.
