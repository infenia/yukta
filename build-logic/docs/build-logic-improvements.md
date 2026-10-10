# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# build-logic — Review, Improvements & Optimisations

> Scope: `build-logic/` (6 precompiled convention plugins, 432 lines after the fixes) reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> **Status (2026-10-10): all findings L1–L14 are fixed** on branch `chore/build-logic-improvements`. Each finding below has a **Resolution** line. The text of each finding is kept as the original review record.
> Companion: [`build-logic-module-guide.html`](build-logic-module-guide.html) is the interactive guide, including the plugin hierarchy and quality-gate matrix.

## 1. Verdict

The convention plugins are well layered:

- `java` → `spring` → `library`, with `quality` and `jacoco` mixed in.
- The version catalog is shared with the main build.
- Quality tooling is unusually thorough: Spotless (google-java-format), Checkstyle, PMD,
  SpotBugs, Error Prone, a licence allow-list (licensee), OpenGrep, and JaCoCo with a
  per-class exception DSL.

The review found weak points in policy and enforcement; all are now fixed:

- **Gates now fail.** OpenGrep runs a pinned local ruleset with `--error`. Trivy no longer runs
  inside `check` (CI owns it; an on-demand root task remains).
- **Coverage is realistic and honest.** Default is LINE 90% / BRANCH 85%. Anything below 80% needs a
  written reason, stale exception patterns fail the build, and disabling verification fails the build.
- **The licence list is deliberate.** Blanket LGPL/GPL-CPE/CDDL/MPL/WTFPL allows are gone; the one
  artifact that needs a copyleft exception (`nashorn-core`) is allowed individually. Legal sign-off
  for native-image distribution is still a human task.
- **Leftovers removed:** milestone repository, `jte-classes`, hard-coded JaCoCo version, duplicate
  test starter, annotation-processor leak, redundant compatibility settings, double version source.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Structure | 4.5/5 | Clean precompiled-plugin hierarchy |
| Tool coverage | 4.5/5 | Unusually complete toolchain |
| Enforcement | 2/5 → 4.5/5 | OpenGrep fails the build. Coverage floor, stale and disabled-verification checks are enforced |
| Supply chain | 3/5 → 4.5/5 | Milestone repo gone, rules are local, licences are per artifact (legal sign-off pending) |
| Build speed | 3/5 → 4/5 | No per-project Trivy, no shell probes |

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
- **Resolution (fixed):** `config/opengrep/.semgrep.yml` now holds four real rules (SpEL `StandardEvaluationContext`, `HostAccess.ALL`, `allowHostClassLookup(s -> true)`, actuator `include: "*"`). The task uses `--config=<file> --error`, scans all of `src/main`, and fails on any finding. Note: the config file did exist on `main`, but only extended registry packs with `rules: []`. Existing hits were suppressed with `nosem` plus a reason, and the actuator exposure was narrowed to `health,info,metrics`. Docs corrected.

#### L2 · P2 · Trivy runs per project on every `check`, never fails, and duplicates CI — Effort S
- **Where:** `tasks.named("check") { finalizedBy("trivy") }` in every module, with `--exit-code 0`.
  `security.yml` in CI also runs Trivy.
- **Fix:** Remove it from the conventions (CI owns it), or register one root-level task that's
  run on demand.
- **Resolution (fixed):** The per-project `trivy` task and `finalizedBy(check)` are gone. The root project has one on-demand `trivy` task (exit code 1 on HIGH/CRITICAL, not wired into `check`); CI `security.yml` keeps scanning.

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
- **Resolution (fixed):** Defaults are LINE 90% and BRANCH 85% (other counters dropped). `coverageConfig.exception(pattern, reason, thresholds)` is the way to lower a class; any ratio below the 80% floor without a reason throws. A stale-pattern check fails verification when a pattern matches no class, and a convention-level check fails when verification is disabled. Stale entries were deleted, entries for classes that already meet the defaults were removed (core, web, mcp), the rest now carry reasons, and verification is re-enabled in `internal-core` and `constant-source`.

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

### 2.4 Resolutions for the table findings

| ID | Resolution |
|---|---|
| L4 | `repo.spring.io/milestone` removed from `java-conventions`. |
| L5 | **Resolution (fixed in code, legal review still open):** LGPL, GPL-CPE, CDDL, MPL, WTFPL and the Nashorn URL were removed from the allow-list. Running `licensee` showed only one artifact needs a copyleft exception: `org.openjdk.nashorn:nashorn-core:15.4` (GPL-2.0 + Classpath Exception), allowed via `allowDependency(...)` with a reason. **Action for a human:** confirm that is acceptable for the statically linked native image. |
| L6 | Covered by L1: rules are a pinned local file, no network at build time. |
| L7 | The OpenGrep probe uses `ProcessBuilder("opengrep", "--version")`; the Trivy probe was removed with L2. |
| L8 | `build/jte-classes` removed from the Error Prone excluded paths. |
| L9 | JaCoCo version moved to `libs.versions.toml` (`jacoco`). |
| L10 | `spring-boot-starter-test` is only in `spring-conventions`. |
| L11 | The `compileOnly extendsFrom annotationProcessor` block was removed; all modules still compile. |
| L12 | `sourceCompatibility` and `targetCompatibility` replaced by `options.release = 25`. |
| L13 | `version` lives only in `gradle.properties`; the root script and the plugin modules no longer set it. |
| L14 | Root `CLAUDE.md` points at `gradle/libs.versions.toml` for the Spring Boot version. |

---

## 3. Roadmap

| Phase | Items | Outcome | Status |
|---|---|---|---|
| **1. Make gates real** (1 day) | L1, L3, L6 | Static analysis and coverage actually block merges | Done |
| **2. Supply chain** (½ day plus legal) | L4, L5, L7 | Fewer repositories, deliberate licence exceptions | Done (legal sign-off pending) |
| **3. Speed and tidy** (½ day) | L2, L8–L14 | Faster `check`, no stale config | Done |
