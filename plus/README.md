# Plus

The Plus edition, licensed **AGPL-3.0-only** (the text is in [`LICENSE`](LICENSE); see [`LICENSING.md`](../LICENSING.md) for what that means in practice).

This module holds the Enterprise module API (`com.infenia.yukta.plus.ext`), the edition information with the source offer (`/actuator/info`) and the store contract tests (`src/testFixtures`). It is added with `-Pyukta.edition=plus`. The durable stores are the nested module `plus/persistence-postgres` (`-Pyukta.persistence=postgres`), and `plus/workers` (`-Pyukta.workers=true`) adds remote workers; both have the same licence. The on-disk stores were removed: PostgreSQL is the only durable store.

Rules for this module: nothing outside `boot` may depend on it, and every file keeps the
`AGPL-3.0-only` SPDX header (Spotless adds it).

## Extending (integration layer)

- `EnterpriseModule` (`com.infenia.yukta.plus.ext`): register a bean to declare a module, its `requiresPlusApi()` (checked against `PlusApi.VERSION`) and its `features()`. `EnterpriseModuleRegistry` refuses a module built for another API version, asks a `LicenseVerifier` (a bean; without one it warns) and `PlusConfiguration` then reports `ENTERPRISE` and the features through `GET /api/edition` and `/actuator/info`.
- A module replaces a default by providing a bean of the same type and declaring `@AutoConfiguration(before = DefaultsAutoConfiguration.class)`; `DefaultsAutoConfiguration` is in `core`.
- Contract tests: `testImplementation(testFixtures(project(":plus")))`, then extend `SessionConfigStoreContract`, `ExecutionLogStoreContract`, `NodeCheckpointStoreContract`, `WorkflowDefinitionStoreContract` or `LeaderElectionContract` and return your implementation (see `ContractsTest`). `SessionConfigStoreContract` covers `SessionConfigStore`; it leaves out what a setter does to a value that is already set, because the stores disagree (bug B31).
- Audit: provide an `AuditSink` bean (core) to receive every control command, applied or not, besides the execution log; a failing sink is logged and ignored.
- Gated settings live in the `GATES` table of `FeatureGateEnvironmentPostProcessor` (`core`): add a row (key, value, provider class, edition, build hint) to make a build refuse a setting whose module is absent.
