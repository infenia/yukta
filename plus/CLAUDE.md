# Plus Module CLAUDE.md

AGPL-3.0-only module (see `../LICENSING.md`): the on-disk stores. Same packages as before the move
(`com.infenia.yukta.service.*`) so component scanning, `@ConditionalOn*` and package-private
helpers keep working; `PlusConfiguration` adds the checkpoint and chat-history files and the
`/actuator/info` source offer.

- Build and test: `./gradlew :plus:test`; end to end: `./gradlew -Pyukta.edition=plus :boot:test --tests '*FileStoreAcceptanceTest'`.
- Nested modules: `persistence-postgres` and `workers` (see their own docs). Only `boot/build.gradle.kts` may depend on `:plus` or `:plus:*` (CI checks it). Never import from here in Apache modules.
- Integration layer: `com.infenia.yukta.plus.ext` (`EnterpriseModule`, `PlusApi.VERSION`, `LicenseVerifier`, `EnterpriseModuleRegistry`) and the contract tests in `src/testFixtures`. Change `PlusApi.VERSION` when either changes in a way that needs a module rebuilt. Hooks themselves stay in `core` (Apache).
- `FileNodeCheckpointStore` stays in `core` (the in-memory store spills to it); Plus only registers it as the durable store.
