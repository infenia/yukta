# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# build-logic — Review, Improvements & Optimisations

> Scope: `build-logic/` (6 precompiled convention plugins, 465 lines) reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> Companion: [`build-logic-module-guide.html`](build-logic-module-guide.html) is the interactive guide, including the plugin hierarchy and quality-gate matrix.

## 1. Verdict

The convention plugins are well layered:

- `java` → `spring` → `library`, with `quality` and `jacoco` mixed in.
- The version catalog is shared with the main build.
- Quality tooling is unusually thorough: Spotless (google-java-format), Checkstyle, PMD,
  SpotBugs, Error Prone, a licence allow-list (licensee), OpenGrep, Trivy, and JaCoCo with a
  per-class exception DSL.

The weak points are policy and enforcement:

- **Two "gates" never fail.**
  - OpenGrep treats findings (exit 1) as success and uses `--config=auto`, pulling rules from the
    network. The `config/opengrep/.semgrep.yml` that `coding-standards.md` refers to doesn't exist.
  - Trivy runs as `finalizedBy(check)` in **every** project with `--exit-code 0`.
- **The 100%-everything coverage default breeds exceptions.** core has 12 exception entries,
  internal-core 13 (five for classes that no longer exist), plus several 0% entries, and some
  modules simply turn `jacocoTestCoverageVerification` off. Nothing detects stale patterns.
- **The licence allow-list includes LGPL-3.0, GPL-2.0-with-CPE, CDDL, MPL and WTFPL.** Those need a
  legal check for a **statically linked GraalVM native image** distributed under Apache-2.0.
- **Leftovers:** the Spring milestone repository (Spring AI 2.0.0 is GA), Error Prone excludes
  `jte-classes`, and the hard-coded JaCoCo version.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Structure | 4.5/5 | Clean precompiled-plugin hierarchy |
| Tool coverage | 4.5/5 | Unusually complete toolchain |
| Enforcement | 2/5 | OpenGrep and Trivy never fail. Coverage is bypassed by exceptions or by disabling it |
| Supply chain | 3/5 | Milestone repo, OpenGrep `--config=auto` over the network, broad licence list |
| Build speed | 3/5 | Trivy per project on every `check`, `which` probes |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days.

### 2.1 Enforcement

#### L1 · P1 · OpenGrep is a gate that can't fail, and its documented config doesn't exist — Effort S
- **Where:** `quality-conventions`, task `opengrep`, with `--config=auto` and
  `if (exitValue > 1) throw`. Findings exit with 1, so they never fail the build. It only scans
  `src/main/java`.
- **Docs drift:** `.claude/rules/coding-standards.md` says the config is
  `config/opengrep/.semgrep.yml`. That directory doesn't exist.
- **Fix:** Commit a ruleset to `config/opengrep/`, use `--config=<file>` with `--error` (or
  `--severity ERROR`), and fail on findings. Point the docs at the real file. Add rules for the
  issues found in this review: `StandardEvaluationContext`, `HostAccess.ALL`,
  `allowHostClassLookup`, `include: "*"`.

#### L2 · P2 · Trivy runs per project on every `check`, never fails, and duplicates CI — Effort S
- **Where:** `tasks.named("check") { finalizedBy("trivy") }` in every module, with `--exit-code 0`.
  `security.yml` in CI also runs Trivy.
- **Fix:** Remove it from the conventions (CI owns it), or register one root-level task that's
  run on demand.

#### L3 · P1 · The coverage policy produces exception sprawl and disabled verification — Effort M
- **Where:** `jacoco-conventions` defaults to 100% across LINE, BRANCH, CLASS, INSTRUCTION and
  METHOD. Modules add `coverageConfig.exceptions`. `MIN_FLOOR` (80%) only **warns**.
- **Evidence:**
  - core has 12 exceptions.
  - plugin-api has 0% for `SpelUtils` and `control.*`, and entries for 2 classes that don't exist.
  - internal-core has 13 entries (5 stale, 2 at 0%) and `jacocoTestCoverageVerification` disabled.
- **Fix:**
  1. A realistic default: LINE 90% and BRANCH 85%. Drop the CLASS, INSTRUCTION and METHOD counters.
  2. Make the floor a hard failure (`< 0.80` → throw) unless the entry has a `reason` string.
  3. Fail the build when an exception pattern matches no compiled class (stale-entry check).
  4. Forbid `enabled = false` on verification tasks with a convention-level check.

### 2.2 Supply chain and licensing

| ID | Finding | Fix | Effort |
|---|---|---|---|
| L4 | `maven("https://repo.spring.io/milestone")` in every project, but Spring AI 2.0.0 and Boot 4.1.1 are GA | Remove it. That's a smaller supply-chain surface and faster resolution | S |
| L5 | Licence allow-list includes `LGPL-2.1+/3.0`, `GPL-2.0-with-classpath-exception`, `CDDL`, `MPL-2.0`, `WTFPL`, and the Nashorn GPL URL. The GraalVM native image statically links all of them | Get a legal review for native distribution. Move weak-copyleft licences to an explicit per-artifact `allowDependency(...)` so each one is a deliberate decision | M |
| L6 | OpenGrep `--config=auto` downloads rules at build time | A pinned local ruleset (see L1) | S |
| L7 | The `which` probe runs `sh -c` through `Runtime.exec`, which fails on Windows (it then just skips) | Use `providers.exec` or `ProcessBuilder("opengrep","--version")` | S |

### 2.3 Clean-up

| ID | Finding | Fix | Effort |
|---|---|---|---|
| L8 | Error Prone `excludedPaths` lists `build/jte-classes` (JTE was removed) | Drop that path | S |
| L9 | JaCoCo `toolVersion = "0.8.14"` is hard-coded | Move it to `libs.versions.toml` | S |
| L10 | `spring-boot-starter-test` is added by both `java-conventions` and `spring-conventions` | Keep only the one in `spring-conventions` | S |
| L11 | `compileOnly extendsFrom annotationProcessor` puts MapStruct and config processors on the compile classpath | Remove it. The freefair Lombok plugin already wires Lombok | S |
| L12 | `sourceCompatibility` and `targetCompatibility` alongside a toolchain | Drop them, or use `options.release = 25` | S |
| L13 | `version = "0.0.1-SNAPSHOT"` globally, but plugins set `version = "1.0.0"` | One version source (`gradle.properties`) | S |
| L14 | The root `CLAUDE.md` says Spring Boot 4.1.0, but the catalog has 4.1.1 | Reference the catalog instead of a hard-coded version | S |

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Make gates real** (1 day) | L1, L3, L6 | Static analysis and coverage actually block merges |
| **2. Supply chain** (½ day plus legal) | L4, L5, L7 | Fewer repositories, deliberate licence exceptions |
| **3. Speed and tidy** (½ day) | L2, L8–L14 | Faster `check`, no stale config |
