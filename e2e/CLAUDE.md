# e2e Module CLAUDE.md

## Overview
A test-only Gradle module (like `architecture`): scenarios that drive a **real Yukta server** over HTTP and MCP. It has no main classes.
The server is the app started once in the test JVM on a random port, or any server you point it at (a jar, the native binary, Docker).
The plan is `design/plans/integration-e2e-testing.md`; the same tiers for the CLI, the UI and the shell smoke checks are in the root
`CLAUDE.md` ("Testing tiers").

## Run it
```bash
yk gradle :e2e:test                                   # in-JVM server: the PR gate
yk gradle :e2e:test --tests 'com.infenia.yukta.e2e.HealthE2eTest'    # a class FQCN (or a package: 'com.infenia.yukta.e2e.<pkg>.*')
yk gradle :e2e:check                                  # the tests plus Checkstyle, PMD and SpotBugs on them

# the same suite against a server that is already running (the key must be an ADMIN key)
release/e2e-server.sh start                           # boot jar, free port, ADMIN and VIEWER keys; `url`, `key` print them
yk gradle :e2e:test -Pyukta.e2e.url=http://127.0.0.1:<port> -Pyukta.e2e.key=<key>
release/e2e-server.sh stop

# the same suite with every store on PostgreSQL (needs Docker: a Testcontainer, postgres:18-alpine)
yk gradle -Pyukta.persistence=postgres :e2e:test
yk gradle -Pyukta.persistence=postgres :e2e:test --tests 'com.infenia.yukta.e2e.postgres.*'   # only the Postgres cases
```
`./gradlew bootRun` on :9860 (see `boot/CLAUDE.md`) works as the external server too.

## Support classes (`src/test/java/com/infenia/yukta/e2e/support/`)
| Class | Use |
|---|---|
| `@E2eTest` | On a test class. `YuktaServer` (a JUnit extension) starts or finds the server and injects `Api` and `Scenarios` into test methods. |
| `Api` | A `WebTestClient` bound to the server with one role's key: `Api.forRole(ApiRole.ADMIN)`. Helpers read the `{timestamp,status,message,path,data}` envelope: `data(response)`, `error(response, status)`. Never retries. |
| `Scenarios` | Domain steps: `applySession(json)`, `startWorkflow(session, workflow, payload)`, `awaitTerminal(execution, timeout)`, `deleteSession`. `Scenarios.unique("test")` returns `e2e-test-<uuid>`. Everything it created is deleted when the test ends. |
| `Mcp` | An MCP SDK client on `/mcp` with one role's key: `Mcp.connect(role)`, `tools()`, `call(name, args)`, `callExpectingError(...)`. Close it to end the session. |
| `@InJvmOnly` | Skips a test or class when the server is an external URL: it needs server properties only the in-JVM server sets (for example the VIEWER and OPERATOR keys, a low rate limit). |

The in-JVM server has every plugin, the process commands `sh` and `echo`, in-memory stores and one key per role. Against a URL only
the ADMIN key exists, so a test that needs another role is `@InJvmOnly`.

## Postgres tier
`-Pyukta.persistence=postgres` builds boot with the PostgreSQL stores; `PostgresDatabase` starts one container per test JVM and every
in-JVM server (the shared one and each `DedicatedServer`) uses it, so a second server sees what the first wrote.
`postgres/PostgresDurabilityE2eTest` (server A writes, server B reads) and `postgres/PostgresHaE2eTest` (leader lease and takeover) run only
in this mode. `DedicatedServer.stop()` really closes a server, which also stops the HTTP of the other servers in the JVM (shared Reactor
resources): read the effect through the database (`PostgresDatabase.lease()`), not through another server's API. Nightly: `postgres-nightly.yml`.

## Conventions
- **Isolation**: every test creates `e2e-<test>-<short uuid>` resources and deletes them; no test relies on order or on another's data (the suite runs against one shared binary).
- **No sleeps**: wait with Awaitility (`awaitility` is in the version catalog).
- **Determinism**: workflows use only `echo`/`sh` and in-JVM plugins. A test that needs an outside service (an LLM, GitHub) skips with an assumption.
- **Build data with the fixtures**: `core` testFixtures (`testing/Fixtures.java`) or the `examples/*/session.json`, not hand-written JSON.
- **Assert the contract**: status codes, the envelope, field names, visible text. Never reach into beans.
- **Run small**: `--tests '<FQCN>'`. The server starts once per test JVM and every class shares it.
- Build and test only through `yk`.

## CI
`ci.yml` runs this module on every PR that touches a platform module (the `e2e` shard).
