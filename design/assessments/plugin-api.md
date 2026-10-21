# Plugin API: assessment

How the `plugin-api` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/plugin-api.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Aplugin-api), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Aplugin-api) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Aplugin-api) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

A suggested order. Step 1 comes first because it protects the ability to stop a run.

1. **Today.** A small, working SPI with 16 commands, a supervisor and control-plane SPI, an expression sandbox with a method allow-list, and shared helpers for run budgets and approval waits. Open work is mostly bounds on control calls and expressions, decoupling plugins from core, and tooling for authors.
2. **Bound the control path.** Add review and decide timeouts with a defined outcome, protect stop from being delayed, and align the two ordering rules. Risks: `reviewNoTimeout`, `decideStall`, `orderOpposite`.
3. **Make plugins independent of core.** Move the resolver and map helpers into plugin-api or a support module, expose the registry and tracker through small SPIs, and add an SPI version with a startup check. Risks: `coreDependency`, `noVersion`.
4. **Tools for plugin authors.** Ship test fixtures with a conformance suite, re-enable PMD for tests, and either call shutdown or remove it. Risks: `noTestKit`, `sharedInstance`, `pmdOff`, `shutdownNever`.
5. **Harden expressions and secrets.** Evaluation time budget (the method allow-list is done), parse outside the lock, a run budget that is reserved before the call, scoped and audited secret access, and a data-only UiDesign. Risks: `spelLimits`, `secretThin`, `uiHtml`, `budgetLedger`.
6. **Typed and reactive extension seams.** Non-blocking store calls (the PostgreSQL stores now make this concrete), declared node signals, and a decision on the directive layer and the unused optimiser hooks. Risks: `storeSync`, `onSignalLoose`, `directiveOnlyStop`, `deadHooks`, `eventsBestEffort`, `signalWaitsShared`.

## Quality lenses

### Scalability

Barely applies to the module itself: it is interfaces and a few static helpers with no threads or I/O. What it does decide is the scaling shape of everything built on it: one shared plugin instance per type, streams per node, and a single in-process supervisor host.

**Ceilings today**

- One shared instance per type means plugin code must be safe under many concurrent nodes; a field-based cache becomes a contention point
- The expression cache has one lock around parsing and lookup, shared by every plugin in the process
- Supervisor events are handled one at a time per control-plane plugin
- Sync checkpoint calls (clear, isComplete, isFinished) would block threads on a remote store

**Levers today**

- Virtual-thread offload for blocking plugins through one flag
- Bounded expression cache (1000)
- Per-config timeout and buffer hooks

**Needs design work**

- A concurrency test kit that runs a plugin under many nodes
- A non-blocking checkpoint interface for database stores
- A cache with less lock contention if expression use grows

### Performance

The helpers sit on the hot path of every message that uses expressions, and parse-once-evaluate-many keeps that cheap. Everything else is interface calls; the cost is in the plugins.

**Ceilings today**

- Each evaluation builds a new evaluation context
- Cache miss parses under the cache lock
- No evaluation timeout: a heavy expression costs whatever it costs
- Messages are immutable; every with-er call copies the envelope

**Levers today**

- Pre-parse hook (preParse) and parse cache
- Default buffer size hook per plugin
- isBlocking keeps blocking work off reactive threads

**Needs design work**

- A benchmark of expression evaluation under load
- Optional per-evaluation time limit
- A guidance note on when to use preParse in prepare

### Availability

Not a runtime component, so it has no uptime of its own. It shapes availability through the supervisor SPI, where a hung reviewer or a slow decide() can stall command handling for a run or a plugin.

**Ceilings today**

- review() has no timeout, and commands of one run wait their turn
- decide() handled one event at a time per plugin; a hang stalls that plugin's feed
- Event feed is best effort and has no replay after a missed event
- shutdown is never called, so resources of shared plugins live until the process ends

**Levers today**

- A failing review counts as a denial instead of an allow
- Supervisor failures are retried with backoff and never stop runs
- A failing decide() counts as no opinion

**Needs design work**

- A review time limit with a defined outcome
- A stop that cannot be vetoed or delayed
- A documented bound for decide() enforced by the default supervise

### Security

This is where the module matters most: it holds the sandbox for user-written expressions, the veto on control commands, and the secret interface. Authentication itself is not here.

**Ceilings today**

- Expression evaluation has no time or size limit and can still call any public method of reachable objects
- SecretProvider has no caller identity, so it cannot scope secrets per plugin or session
- UiDesign is raw HTML supplied by plugin code and rendered by the UI
- Review vetoes depend on supervisors being present and correct

**Levers today**

- Restricted evaluation context: no static calls, constructors or bean references, with tests
- Breakpoint conditions are declarative, not code
- SECRET field type and decrypted:&lt;key&gt; convention
- Denial on review error

**Needs design work**

- An expression budget (time and size)
- Secret access scoped by plugin and audited
- A sanitising or sandboxed rendering of UiDesign HTML

### Operability

The module offers metrics hooks (a reporter interface) and an actor in the command audit, but ships no tooling for plugin authors: no test kit, no lifecycle that tells a plugin when to clean up.

**Ceilings today**

- No test fixtures for plugin authors
- pmdTest is disabled
- A plugin cannot know it is being stopped except by stream cancel
- Config schema and the code that reads it are kept aligned by hand

**Levers today**

- PluginMetricsReporter with a NOOP default and per-node OK/ERROR counts through PluginGuard
- Actor id in the command audit (ControlActor)
- Tests that pin defaults and sandbox rules

**Needs design work**

- A test kit (harness and message builders)
- A conformance test every plugin can run
- Re-enable pmdTest

### Evolvability

This is the module's biggest long-term risk: a wide set of defaults that every plugin silently relies on, no version marker, and plugins that also compile against core internals.

**Ceilings today**

- Adding an abstract method breaks every plugin; adding a command breaks every exhaustive switch
- No SPI version, so mismatches show up as runtime errors
- Plugins import VariableResolver, MapUtils, PluginRegistry and the tracker from core
- WorkflowDirective has one member, so its sealed hierarchy gives little extension room yet

**Levers today**

- Default methods for new hooks
- Sealed types that make missing handling a compile error
- Small module with few dependencies

**Needs design work**

- Move the shared helpers plugins need into plugin-api or a support module
- An SPI version constant and a startup check
- A decision on whether directives or processor-side effects are the extension seam

## Questions to settle

- **Availability.** A supervisor's review calls a slow policy service. What should happen to a human's stop command while it waits? (risk `reviewNoTimeout`)
- **Evolvability.** You must add a 17th control command. List everything in the repository that breaks and decide if that is a feature or a cost. (risk `directiveOnlyStop`)
- **Correctness.** Write the smallest test that proves a plugin is safe to share between two nodes with different configs. (risk `sharedInstance`)
- **Security.** Name three expressions you would try against the sandbox that are not in the tests. Which layer would stop each? (risk `spelLimits`)
- **Reliability.** A rule must pause a workflow after 3 failures but the feed drops under load. How would you guarantee it? (risk `eventsBestEffort`)
- **Evolvability.** An external team wants to ship a plugin as a jar. Which parts of today's contract and dependencies would stop them? (risk `coreDependency`)
- **Performance.** A PostgreSQL checkpoint store answers isFinished in 5 ms. What does that cost a resume of a 200-node run? (risk `storeSync`)
- **Functionality.** How would an agent discover that a plugin accepts an 'approve' signal and what payload it needs? (risk `onSignalLoose`)
- **Security.** Two teams share one server. How would you stop team A's plugin from reading team B's secrets? (risk `secretThin`)
- **Operability.** Which lifecycle hook should release a database pool, given that shutdown is never called? (risk `shutdownNever`)

## Last risk review

IDs such as G18 and G22 refer to the plugins module's improvements list, which cross-references this module. The rest are findings from reading the code. Review of 2026-10-15 against main: no risk was closed; the SpEL sandbox risk is partly closed (method allow-list), the store risk is now concrete (PostgreSQL stores), the core-coupling risk grew slightly, and two risks were added for the new shared run-budget and approval-wait helpers.
