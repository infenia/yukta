# Plus Module CLAUDE.md

AGPL-3.0-only module (see `../LICENSING.md`): the edition information and the `/actuator/info`
source offer (`PlusConfiguration`), the Enterprise module API and the store contract tests. The
on-disk stores were removed; PostgreSQL (`persistence-postgres`) is the only durable store.

- Build and test: `yk gradle :plus:test`.
- Nested modules: `persistence-postgres` and `workers` (see their own docs). Only `boot/build.gradle.kts` may depend on `:plus` or `:plus:*` (CI checks it). Never import from here in Apache modules.
- Integration layer: `com.infenia.yukta.plus.ext` (`EnterpriseModule`, `PlusApi.VERSION`, `LicenseVerifier`, `EnterpriseModuleRegistry`) and the contract tests in `src/testFixtures`. Change `PlusApi.VERSION` when either changes in a way that needs a module rebuilt. Hooks themselves stay in `core` (Apache).
