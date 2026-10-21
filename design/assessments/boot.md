# Boot: assessment

How the `boot` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/boot.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Aboot), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Aboot) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Aboot) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

Boot's path is mostly about making the safe choice the default and proving the shipped artifact works.

1. **Today.** One process, loopback by default with a start-up guard, dev default on the JVM, prod forced on native, all plugins except script-js by default, in-memory state unless a persistence or workers build is chosen.
2. **Safer defaults.** Done: refuse to bind publicly without a key, guard /actuator. Next: fix the guard for the MCP-key-only case, guard or switch off Swagger, prod as the default profile, trim dev exposure. Risks: `guardMcpKeyOnly`, `profileDefault`, `devExposure`, `openOps`.
3. **Verify the shipped artifact.** Smoke-test the native binary and its profile; measure the build heap; make tests independent of plugin choice. Risks: `nativeGap`, `xmx8g`, `testsNeedPlugin`, `profileForce`, `nativeMetaStale`.
4. **Persistent profile.** A documented profile with file stores or a database, and clean-up of dead keys. Risks: `singleProc`, `deadKeys`.
5. **Flexible packaging.** Prebuilt plugin variants or a plugin directory for JVM builds. Risks: `buildFixed`, `scanBroad`.

## Quality lenses

### Scalability

Boot adds nothing that scales: one process, one port. All scaling questions belong to core. It does not block scale-out, but it offers no help either.

**Ceilings today**

- One JVM, one Netty server, one port for API, MCP and management
- In-memory defaults mean copies share nothing
- Native image fixes the plugin set
- Management traffic shares the API pool

**Levers today**

- Virtual threads for blocking work
- Gzip in prod
- Long cache for hashed assets

**Needs design work**

- A documented multi-instance setup, or an explicit statement that it is single-node
- A separate management port
- A CDN or ETag strategy for assets if traffic grows

### Performance

Start-up and memory are the performance topics here. Native image gives the best start-up; the JVM is easier to build.

**Ceilings today**

- The native build needs a big heap and time
- index.html is read per request
- Debug logging with payloads in dev costs CPU and disk

**Levers today**

- Native image with AOT
- 365-day cache of hashed assets
- Eager start-up that finds faults early

**Needs design work**

- Measured start-up and memory numbers per mode
- Cache the index file in memory
- A budget for build time and heap

### Availability

Nothing in boot restarts, replicates or drains. Availability comes from the platform that runs the process, and from core's recovery after a restart.

**Ceilings today**

- No graceful shutdown setting is configured here
- Defaults lose state on restart
- Health detail is probably hidden

**Levers today**

- Health endpoint in every profile
- Eager start-up so bad config never half-starts
- Fail-fast build for plugin typos

**Needs design work**

- Readiness vs liveness split
- A shutdown grace period set explicitly
- A persistent-state profile

### Security

The strongest lens for this module. Since the first guide the start-up guard, a guarded actuator and Host/Origin checks were added; what is left is the dev default, the open docs, and one gap in the guard.

**Ceilings today**

- Swagger and /v3 are outside any key
- The guard accepts yukta.mcp.api-key alone, which leaves /api open
- Default JVM profile is dev
- Keys are optional on loopback

**Levers today**

- Loopback by default, and a start-up refusal on other addresses without a key
- Actuator guarded by an ADMIN key; no heapdump or env exposure
- CSP and no-cache on index.html
- Profile test guards prod config

**Needs design work**

- Prod-by-default or a refusal to bind publicly in dev
- Guard the docs, or switch them off in prod
- A guard that checks the keys the filters use

### Operability

Good basics: three profiles' worth of logging, request ids in log lines, a health URL. Missing is guidance and guard rails for production.

**Ceilings today**

- Config split across YAML, environment and build flags
- Keys that nothing reads
- Which plugins are in a given binary is not visible at run time

**Levers today**

- Console logs with request id
- Health, info and metrics
- Documented run commands

**Needs design work**

- Expose the build's plugin list in info
- Remove or implement dead config keys
- A production example config

### Evolvability

Adding a module or plugin is one line in the build file, which is cheap. The weak spot is that behaviour is spread across the build flag, YAML and a launcher rule.

**Ceilings today**

- Plugin list is a hand-kept map in the build script
- Profile forcing is in code and in the build
- Legacy /sse name lingers

**Levers today**

- Single place to add modules
- Tests for config and launcher rules
- Fail-fast validation of plugin names

**Needs design work**

- Derive the plugin list from the module tree
- Decide and document the profile policy
- Retire the /sse alias on a schedule

## Questions to settle

- **Security.** The server is exposed on 0.0.0.0 with an API key and no profile. List what a stranger can read (hint: the docs), and what changes if only yukta.mcp.api-key is set. (risk `openOps`)
- **Security.** Which of our default settings would you change first to be safe in production, and what breaks for developers? (risk `profileDefault`)
- **Scale.** You must run three copies behind a load balancer. What does each copy hold that the others cannot see? (risk `singleProc`)
- **Packaging.** A customer wants a build without shell execution and without JavaScript. What do they type and what can they not change later? (risk `buildFixed`)
- **Reliability.** A native-only crash appears after a release. How would we have caught it? (risk `nativeGap`)
- **Operability.** An operator asks which plugins are in the binary. Where do they look? (risk `scanBroad`)
- **Web.** A client calls /apis/workflows by mistake. What does it get and is that fine? (risk `apiPath200`)
- **Caching.** We replace favicon.ico. When do users see it? (risk `staticStale`)
- **Config.** How would you prove every key in application.yaml is read by something? (risk `deadKeys`)
- **Testing.** Someone builds with -Pyukta.plugins=none. What tells them tests can't pass? (risk `testsNeedPlugin`)

## Last risk review

Risk ids are short names; no backlog numbers are used. The older guide's B-series review findings were all marked fixed and are not repeated. Review of 2026-10-15 against main (after PRs #368 to #374): 1 risk closed (noKeyContainer, by ExposureGuard), 4 partly closed (openOps, devExposure, singleProc, buildFixed) plus xmx8g restated for the new 16 GB value, 2 added (guardMcpKeyOnly, nativeMetaStale); the others are unchanged because their code is unchanged.
