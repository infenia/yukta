# Build logic: assessment

How the `build-logic` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/build-logic.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Abuild), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Abuild) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Abuild) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

Order is by cheap trust gains first: make what the build says true, then close bypasses, then make it reproducible, then speed it up.

1. **Today.** Six plugins, one catalog, 27 modules. Gates fail the build; OpenGrep needs the program installed; one coverage exception; no lock files; Netty now comes from the catalog; CI builds in four shards.
2. **Make the docs and versions honest.** Correct the docs from the code, delete the stale Netty line, fix the nashorn reason text, and decide whether pull requests should block on vulnerabilities. Risks: `nettyTwoSources`, `docDrift`, `legalSignoff`, `trivyPrGate`, `cvePins`.
3. **Close the escape hatches.** Guard every quality task against being disabled, fail on missing coverage data, burn down the three temporary disables, check build-logic itself. Risks: `disabledTasks`, `coverageNoData`, `selfUnchecked`.
4. **Reproducible and visible.** Commit lock files and turn on the CI check; warn when OpenGrep is skipped; add a toolchain resolver; align Node and pnpm in CI. Risks: `noLockfiles`, `opengrepSilentSkip`, `noToolchainResolver`, `nodeDrift`.
5. **Faster and sharper.** Run OpenGrep once with declared outputs, tune Error Prone and SpotBugs, generate the CI module map, and decide whether 100% per class stays. Risks: `opengrepEveryModule`, `advisoryTools`, `ciManualMap`, `strict100`, `fullBuildOnRoot`.

## Quality lenses

### Scalability

Scales with module count, not users. Each of 27 modules runs its own analysis, and the checks that cannot be cached (OpenGrep, which declares no outputs) grow linearly. CI runs a whole-repo build whenever a root file changes.

**Ceilings today**

- OpenGrep runs one module at a time (a shared service) but is cacheable, so unchanged modules are skipped
- A catalog, config or build-logic change triggers a full CI build
- One CI runner with a 30-minute timeout
- Test forks are limited by memory

**Levers today**

- Gradle build cache and parallel execution
- Path-filtered PR builds with buildDependents
- Test forks scaled to CPUs / 2

**Needs design work**

- Run OpenGrep once at the root or give it declared outputs
- Split a full build across runners if it nears the timeout
- Remote build cache for CI and laptops

### Performance

A local ./gradlew check is the developer's inner loop, so its time is what matters. Rules are heavy (five analysers, per-class coverage, a scan), and CLAUDE.md steers developers toward the smallest test scope.

**Ceilings today**

- Static analysis is not incremental across the board
- Coverage verification depends on every test finishing
- Configuration cache is off (Go tasks are incompatible)
- Test JVMs run at the lowest compiler tier

**Levers today**

- Build cache on; parallel on with 8 workers
- JaCoCo reports built only with -Pcoverage
- Tests in parallel forks, ParallelGC

**Needs design work**

- Turn on the configuration cache for modules that allow it
- Measure which gate dominates check time
- Exclude test sources from the slowest analysers if needed (judgement)

### Availability

Barely applies to production, since build-logic runs nowhere at run time. It matters for the availability of the build: a broken convention plugin stops everyone, and CI downloads tools and rules at run time.

**Ceilings today**

- One broken convention script fails every module
- CI depends on GitHub, Maven Central, the Gradle plugin portal and tool download sites
- No JDK 25 means no build

**Levers today**

- OpenGrep rules are local, so no rule download
- Pinned Action SHAs and a checked OpenGrep binary
- The wrapper jar is validated

**Needs design work**

- A mirror or cache for plugin and dependency downloads
- A toolchain resolver or documented JDK setup
- Dependency lock files to make restores deterministic

### Security

This is where build-logic earns its keep: it is the supply-chain control point. It bans four dangerous code patterns, enforces a licence list, pins CVE-affected versions, and CI pins tools by checksum or commit hash.

**Ceilings today**

- Locking is declared but no lock files exist, so resolved versions are not recorded
- Vulnerability scan blocks only on main, not on pull requests
- Four OpenGrep rules cover few risk patterns
- A stale Netty line remains in gradle.properties; the resolved version is not asserted

**Levers today**

- Local pinned OpenGrep rules with --error
- Licence allow-list with one per-artifact exception
- Netty BOM imported after Boot; plexus-utils pinned
- Secret scan blocking on every run; dependency review on PRs

**Needs design work**

- Commit lock files and enable the CI lock check
- Remove the stale Netty line and add a check of the resolved version
- More OpenGrep rules for the plugin sandbox and file access
- Legal sign-off for the nashorn-core exception

### Operability

Developer experience: clear failures, one command to fix formatting, and a documented way to add a module. The weak spots are silent skips and docs that disagree with the code.

**Ceilings today**

- Silent skip when opengrep is missing
- Docs say 90/85 while code enforces 100/100
- Failure messages come from several tools in different styles

**Levers today**

- spotlessApply fixes formatting
- Reasoned coverage exceptions and stale-entry errors with clear messages
- CI lint reports a project() dependency on a module missing from settings

**Needs design work**

- Print a warning when OpenGrep is skipped
- Keep CLAUDE.md and guides in step with the code
- Generate the CI module list from settings.gradle.kts

### Evolvability

Good: rules are in one place, versions in one catalog, and the plugins are small and layered. The cost is that a rule change hits all 27 modules at once, and a few module-level escapes exist.

**Ceilings today**

- A new strict rule blocks every module at once
- spring-conventions is the de facto standard bundle (naming)
- Quality tooling is Java only
- Escape hatches in module build files

**Levers today**

- Layered plugins
- Reasoned exception DSL
- Config files separate from code

**Needs design work**

- A rollout path for new rules (warn first, then fail)
- A guard against disabling quality tasks
- A decision on how Kotlin build code is checked

## Questions to settle

- **Security.** If the Netty override and the catalog disagree, how would you notice in a pull request, and what would you add to make it impossible? (risk `nettyTwoSources`)
- **Security.** Lock files are declared but absent. What would a locked build protect you from that BOM pinning does not? (risk `noLockfiles`)
- **Operability.** A new contributor never installs OpenGrep. What do you want to happen locally: nothing, a warning, an error, or an auto-download? (risk `opengrepSilentSkip`)
- **Governance.** Three modules disable some analysers with a 'temporarily disabled' comment. Who should own burning those down, and what would make the temporary disable expire? (risk `disabledTasks`)
- **Correctness.** 100% line and branch per class: what tests have you seen written only to reach a branch, and would you accept them? (risk `strict100`)
- **Governance.** Would you accept the nashorn-core exception for a native image without a written legal opinion? What is the smallest evidence you would want? (risk `legalSignoff`)
- **Performance.** Check time on a full repo is mostly waiting. Which gate would you measure first, and what would you change if OpenGrep were the slowest? (risk `opengrepEveryModule`)
- **Evolvability.** A new Checkstyle rule would fail 40 files in 10 modules. How should it be introduced without blocking everyone? (risk `advisoryTools`)
- **Availability.** GitHub or Maven Central is down for an hour. Which parts of the pipeline break, and which would you mirror first? (risk `fullBuildOnRoot`)
- **Operability.** If the CI module map were generated from settings.gradle.kts, what could still go wrong when someone adds a module? (risk `ciManualMap`)

## Last risk review

IDs such as L5 refer to the earlier build-logic review ([Improvements page](./build-logic.md)). Its other findings, L1 to L14 except L5, are fixed in code and are not repeated here. Review of 2026-10-15 against main (after PRs #373, #374 and #391): 0 risks closed, 3 partly closed (nettyTwoSources, opengrepEveryModule, fullBuildOnRoot), 4 updated (disabledTasks, ciManualMap, nodeDrift, trivyPrGate), 1 added (cvePins); the other 9 are unchanged because their files are unchanged.
