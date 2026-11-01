---
paths:
  - "src/**/service/supervisor/**"
  - "src/**/service/control/directive/**"
---

# Supervisors (rule engine, AI or custom code on the control bus)

Reference for `ExecutionSupervisor`, `ControlReviewer` and the event feed. Moved from `core/CLAUDE.md`.

### Supervisors (rule engine / AI / custom code on the control bus)
A supervisor implements `ExecutionSupervisor` (plugin-api) and is registered as a Spring bean:
- `supervise(Flux<ExecutionEvent>)` receives the global event feed (`ExecutionEventFeed`, fed by the
  tracker and by command outcomes; best effort, never blocks a run) and returns the
  `ExecutionControlCommand`s to apply. `SupervisorHost` subscribes each supervisor once, applies its
  commands through `DirectiveDispatcher` with the supervisor id as actor (Reactor context key
  `ControlActor.CONTEXT_KEY`), and isolates it: failures resubscribe with backoff, commands are
  limited to `yukta.supervisor.max-commands-per-second` (default 20), `yukta.supervisor.dry-run=true`
  only records them, and a supervisor never sees the outcome of its own commands.
- `review(ControlRequest)` can refuse any command, human or automated, before it runs
  (`ControlReviewer`; first refusal wins in `order()`; an erroring reviewer refuses). The caller gets
  `ControlRejectedException` (HTTP 403) and the log gets `CONTROL_REJECTED`.
- Commands sent by a supervisor are logged as `PauseWorkflowCommand [by <id>]`.
- The feed delivers on the publisher's thread, so each supervisor gets its events through a
  bounded buffer (256) on `boundedElastic`; a slow or blocking supervisor drops events (counted in
  `yukta.tracker.dropped{kind=supervisor}`) and never holds the tracker up.
- The feed is exposed at `GET /api/events/stream` (SSE; filters `executionId`, `type`). External
  agents can supervise through it and the REST/MCP control endpoints without JVM code.
- `SupervisorHost.status()` (REST `GET /api/supervisors`, shown on the UI Control page) reports each supervisor's kind (`CONTROL_PLANE` or `SUPERVISOR`), order, dry-run flag and command counts from `yukta.supervisor.commands`.
- Reference implementation: `plugins/supervisors/rule-supervisor` (JSON rules + SpEL, enabled with
  `yukta.supervisor.rules.file`). Metrics: `yukta.supervisor.commands{id,outcome}`.
- **Run-time supervisors**: a `SupervisorProfile` (type, settings, dry-run) plus a `SupervisorBinding` (profile, `SupervisorScope`, enabled) made through `SupervisorService` start a `BoundSupervisor` in `SupervisorHost.attach`; `AttachedSupervisors` is the set `ControlReviewer` also asks. Factories (`SupervisorFactory` beans: `rule-supervisor`, `rule-control-plane`, `model-control-plane`, `rules-first-control-plane`) turn a profile into the supervisor. The store (`SupervisorStore`) is in memory, or PostgreSQL (V7). At most 50 bindings; attach/detach are audited under run id `supervisors`; `SessionService.deleteSession` removes the session's bindings; `SupervisorHost.decisions` keeps the newest 500 command outcomes.
- **Id rule**: profile ids, binding ids and bean `ExecutionSupervisor.id()` match `SupervisorIds.RULE` (`[A-Za-z0-9._-]{1,64}`, plugin-api; no `:` or whitespace, so an id never looks like `key:<name>`). Enforced in `SupervisorService.saveProfile`/`attach` (400 through `IllegalArgumentException`), `SupervisorHost.start` (bean with a bad id fails start-up) and `restore` (a stored bad binding is skipped with a WARN). Not in the record constructors: stores rebuild stored rows before migration V8 (`supervisor_id_migration` keeps old to new) has run.
- **Capabilities** (`SupervisorCapabilities.from(profile, factory.defaultAllowedCommands())`, read when a profile is saved (an unknown command name is refused) and when its binding starts; carried on `BoundSupervisor`): `allowedCommands` (`null` = every command, the default of every shipped factory), `canAnswer` (default false) and `maxRisk` (default LOW). `SupervisorHost` drops a command whose simple class name is not allowed before the rate limit, counts it `yukta.supervisor.commands{outcome=forbidden}` and records it in the decisions with the reason. Bean supervisors are unrestricted and can never answer input requests.
- **Actor kind**: `SupervisorHost` writes `ControlActor.KIND_KEY = SUPERVISOR` next to the supervisor id; input authorisation reads the kind, never the actor string.
