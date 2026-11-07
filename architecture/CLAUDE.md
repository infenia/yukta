# Architecture Module CLAUDE.md

Test-only module with the ArchUnit rules for the Apache modules. Reference: `docs/contributing/architecture.mdx`.

- Run: `yk gradle :architecture:test` (the Plus modules: `yk gradle :plus:plus-architecture:test`).
- Layout: `src/testFixtures` holds what `plus-architecture` reuses (`Modules`, `Scopes`, `HygieneRules`, `ReactiveRules`, `DeprecationRules`, `StructureRules`); `src/test` holds the module-specific rule holders (`ModuleRules`, `CoreRules`, `WebRules`), one runner per scope (`*ArchitectureTest`), the self-tests (`RuleSelfTest`, `ModuleLocationTest`) and the guards (`ModuleCoverageTest`, `NoEscapeHatchesTest`).
- Layers are found by the directory a class was built in (`Modules`), not by package: modules share packages.
- The module depends on every Java module. A new module in `settings.gradle.kts` must be added to `build.gradle.kts` (`ModuleCoverageTest` fails otherwise); it must not depend on `:plus*` (only boot and `plus/**` may).
- Policy: violations are fixed. No freezing, no `@ArchIgnore`, no weakening a rule to make a build pass. Exemptions (blocking calls, deprecated API, plugin edges) are short maps with a reason; adding one needs a maintainer's review.
- Core's package layout is `src/test/resources/core-packages.puml`; change it together with the code and explain why.
- PMD/Checkstyle apply here too: package-private members carry `/* default */`, repeated literals are constants, rule holder classes are `final` with a private constructor.
