# Plus Module CLAUDE.md

AGPL-3.0-only module (see `../LICENSING.md`): the on-disk stores. Same packages as before the move
(`com.infenia.yukta.service.*`) so component scanning, `@ConditionalOn*` and package-private
helpers keep working; `PlusConfiguration` adds the checkpoint and chat-history files and the
`/actuator/info` source offer.

- Build and test: `./gradlew :plus:test`; end to end: `./gradlew -Pyukta.edition=plus :boot:test --tests '*FileStoreAcceptanceTest'`.
- Only `boot/build.gradle.kts` may depend on `:plus` (CI checks it). Never import from here in Apache modules.
- `FileNodeCheckpointStore` stays in `core` (the in-memory store spills to it); Plus only registers it as the durable store.
