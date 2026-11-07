---
paths:
  - "architecture/**"
  - "plus/plus-architecture/**"
  - "settings.gradle.kts"
  - "**/build.gradle.kts"
---

# Architecture rules (ArchUnit)

The structure of the project is enforced by tests (`docs/contributing/architecture.mdx`). When you add a module, a dependency, a plugin or a package, respect them, or the build fails:

- **Direction of dependencies**: `messaging` -> `plugin-api` -> `core` -> `web` / `mcp`; plugins depend on `plugin-api` and `messaging` only. Only `boot` wires everything; only `boot` and `plus/**` may depend on `:plus`.
- **A new plugin-to-plugin dependency** is a design decision: it needs an entry in `ModuleRules.ALLOWED` (a maintainer reviews it). Prefer going through `plugin-api`.
- **A new Java module**: add it to `settings.gradle.kts` and to `architecture/build.gradle.kts` (not under `:plus*`: add those to `plus/plus-architecture/build.gradle.kts`).
- **Core packages**: lower packages (`model`, `validation`, `util`, `event`, `edition`, `config`) must not use `service`; `service` must not use `startup`. Wiring configurations (`AppConfiguration`, `DefaultsAutoConfiguration`) live in `startup`; value types that properties reference live in `model`. See `architecture/src/test/resources/core-packages.puml`.
- **Code**: no `Mono/Flux.block*` or `Thread.sleep` in production code, no generic `RuntimeException`/`Exception` thrown (use `WorkflowExecutionException`, `IllegalArgumentException`, ...), no Jackson 2 (`com.fasterxml.jackson.databind`), no field injection, no hand-written loggers (`@Slf4j`), no deprecated API.
- A failing architecture test is fixed in the code. Never freeze it, add `@ArchIgnore`, or weaken the rule. If the structure must change, change the rule or the diagram in the same PR and explain why.
- Run: `yk gradle :architecture:test :plus:plus-architecture:test`.
