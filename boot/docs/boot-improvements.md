# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# boot — Review, Improvements & Optimisations

> Scope: `boot/` (2 Java classes, 5 resource files, the native-image config and `build.gradle.kts`)
> reviewed on 2026-10-09 against `main` @ `8d3161b5`. **Updated 2026-10-10:** B1–B8 and B10 are
> implemented (marked ✅ below). B9 and B11 remain open.
> Companion: [`boot-module-guide.html`](boot-module-guide.html) is the interactive guide, including a profile/config matrix.

## 1. Verdict

`boot` assembles the product. It pulls in core, web, mcp, ui and every plugin. It serves the React
SPA, picks a profile, and builds the GraalVM native image. The Java code is small and well tested
(`SpaWebFluxConfig` correctly keeps `/api`, `/actuator` and `/sse` out of the SPA fallback).

The configuration risks found in the review are fixed. What remains:

- **B9:** GraalJS/polyglot is still bundled in the native image.
- **B11:** every plugin is still hard-wired into `boot/build.gradle.kts`.
- No authentication on the remaining actuator endpoints (web W1). Only `health`, `info`,
  `metrics` and `prometheus` are exposed now, and `ProfileConfigTest` guards that.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Assembly | 4/5 | Clear module wiring. SPA routing is tested |
| Security posture | 3.5/5 | Actuator limited; still no auth (web W1) |
| Config hygiene | 4.5/5 | Profile overrides verified by test |
| Native image | 3.5/5 | Stale JTE gone; GraalJS still bundled (B9) |
| Tests | 4/5 | Application, SPA config and profile-config tests |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day.

### 2.1 Security

#### B1 · P0 · Every actuator endpoint is exposed, including heapdump — Effort S ✅
- **Status:** ✅ done — limited to health, info, metrics, prometheus; `*` only in `application-dev.yaml`; `show-details: when-authorized`.
- **Where:** `application.yaml` → `management.endpoints.web.exposure.include: "*"`.
- **Impact:** `GET /actuator/heapdump` downloads process memory: API keys from workflow configs
  (plugins G5), payloads, session data. `POST /actuator/loggers/...` can switch on TRACE payload
  logging. `/env` and `/configprops` reveal configuration. There's no auth (web W1).
- **Fix:**
  ```yaml
  management:
    endpoints.web.exposure.include: health,info,metrics,prometheus
    endpoint.health.show-details: when-authorized
  ```
  Put full exposure only in `application-dev.yaml`, or on a separate `management.server.port`
  bound to localhost.

#### B2 · P2 · Session and log data default to `/tmp/.yukta` — Effort S ✅
- **Status:** ✅ done — removed the `/tmp/.yukta` override; the `SessionConfigProperties` default `${user.home}/.yukta` applies (mode 700 creation lives in core and is not done here).
- **Where:** `yukta.session.base-dir: /tmp/.yukta`.
- **Impact:** On shared hosts other users can pre-create or read the directory, and `/tmp` is
  wiped on reboot, so the file store loses sessions.
- **Fix:** Default to `${user.home}/.yukta` (or `$XDG_STATE_HOME/yukta`), create it with mode
  `700`, and keep `/tmp` only for tests.

### 2.2 Configuration correctness

#### B3 · P1 · TRACE payload logging stays on in prod — Effort S ✅
- **Status:** ✅ done — base config is INFO; DEBUG and the stream TRACE moved to `application-dev.yaml`; `ProfileConfigTest` asserts both profiles.
- **Where:** In `application.yaml`, `logging.level.com.infenia.yukta: DEBUG` and
  `com.infenia.yukta.service.orchestrator.stream: TRACE`. `application-prod.yaml` overrides only
  `com.infenia.yukta: INFO`.
- **Impact:** Logback picks the most specific logger, so `...orchestrator.stream` stays at TRACE
  in prod and every message payload is logged ("Processing message payload: …").
- **Fix:** Keep the base config at INFO and move DEBUG and TRACE into `application-dev.yaml`.
  Add a test that loads the `prod` profile and asserts the stream logger level is at least INFO.

#### B4 · P2 · `lazy-initialization: true` in prod hides startup errors — Effort S ✅
- **Status:** ✅ done — removed `lazy-initialization` from prod.
- **Where:** `application-prod.yaml`.
- **Impact:** Misconfigured beans (a plugin with bad config, or a missing MCP dependency) fail on
  the first request instead of at startup. `@PostConstruct` sink wiring in
  `DefaultTaskTrackerService` and `ControlBusService` is deferred until first use. The native image
  already starts fast, so the gain is small.
- **Fix:** Remove it, or exclude the core services with `LazyInitializationExcludeFilter`.

#### B5 · P3 · Duplicate and dead keys — Effort S ✅
- **Status:** ✅ done — removed the duplicated MCP/virtual-thread/banner keys from dev, `yukta.daemon.*`, `spring.docker.compose.enabled`, the docker-compose dependency, the `bootRun` profile arg and the "database" backend comment.
| Key | Problem |
|---|---|
| `spring.ai.mcp.server.*` | Repeated in `application.yaml` and `application-dev.yaml` |
| `spring.threads.virtual.enabled`, `banner-mode` | Repeated in every profile |
| `yukta.daemon.port / startup-timeout-seconds / health-check-interval-ms` | No Java consumer. If the Go CLI needs them, document that there |
| `spring.profiles.default: dev` + `bootRun args --spring.profiles.active=dev` | Redundant |
| `spring.docker.compose.enabled: false` + `developmentOnly(docker-compose)` | The dependency is added and then disabled. Drop both |
| `yukta.logs.store.backend: ... database (when available)` | Advertises a backend that doesn't exist |

### 2.3 Native image and build

| ID | Finding | Fix | Effort |
|---|---|---|---|
| B6 ✅ | `reflect-config.json` registers `gg.jte.*` classes, and the build comment says "JTE classes need more memory", but JTE was removed when the UI moved to React | Delete the JTE entries. Keep only what the AOT tracing agent reports | S |
| B7 ✅ | The prod profile is forced in three places: `native-image.properties` `Args=-Dspring.profiles.active=prod`, `graalvmNative buildArgs`, and `YuktaApplication.main` (`isNativeImage()`) | Keep one. The `buildArgs` entry is the clearest. Delete the `main()` logic and its test branches | S |
| B8 ✅ | `-J-Xmx16g` for the native build | Measure, then lower it (4–8 GB is typical for a Boot app). 16 GB fails on CI runners | S |
| B9 | GraalJS and polyglot are in the image (from the internal-core mapper) | After plugins G1, consider making SCRIPT mode an optional plugin module to shrink the native image | M |
| B10 ✅ | `logback-spring.xml` sends all logs to **STDERR**. The original reason (MCP stdio) no longer applies because `stdio: false` | Use Boot's default console appender, or document why stderr is needed | S |
| B11 | Every plugin is hard-wired in `boot/build.gradle.kts`, including the WIP http-request, llm-call and session-memory | Fine for now. Later, a `plugins` BOM or a classpath directory lets you ship optional plugins | M |

---

### 2.4 Implementation notes (2026-10-10)

- **B6:** deleted `reflect-config.json`. No tracing-agent config was added, so run the agent if the native build needs reflection hints.
- **B7:** only `graalvmNative` buildArgs sets prod now. Deleted `native-image.properties` and the `main()` logic with its tests.
- **B8:** lowered to `-J-Xmx8g`. Not measured locally. Raise it if the native build runs out of memory.
- **B10:** deleted `logback-spring.xml`. Boot's console appender and the yaml pattern are used.
- **Not run:** `nativeCompile`.

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Secure defaults** ✅ | B1, B3, B2 | No heap dumps or payload logs in prod |
| **2. Config hygiene** ✅ | B4, B5, B10 | One source of truth per setting |
| **3. Native image** (B6–B8 ✅, B9 open) | B6, B7, B8, B9 | Smaller image, cheaper build |
| **4. Packaging** (later) | B11 | Optional plugins |
