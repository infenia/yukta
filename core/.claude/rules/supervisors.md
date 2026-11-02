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
- `ExecutionEventFeed.events()` gives every subscriber its own buffer (10 000, overflow counted in
  `yukta.tracker.dropped{kind=feed}`) on `boundedElastic`, so none runs on the tracker's thread. On top of that each supervisor gets its events through a
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
- **Scopes** (`SupervisorScope`, plugin-api): `Global`, `Session`, `Workflow`, `Section(workflowId, sessionId, nodes)`. Text form `workflow:[<s>/]<w>//<sel>,<sel>` (ids cannot contain `/`, so the first `//` starts the selectors; they may contain `#`, which is why `#` is not the separator); a selector is a node id or `<prefix>.*` (node ids starting with `<prefix>.`), 1 to 50, at most 256 chars, no comma. Stored as the same text (no migration). `matches(session, workflow, node)` (the default ignores the node); `Section.matches(session, workflow)` is false, so a section never sees or reviews workflow-wide items (workflow events, `Pause/Resume/StopWorkflowCommand`, priority, restarts). `covers` / `strictlyCovers` (covers and not covered back; a strict partial order) are for composites, holds and escalation.
- **Node of an item**: `ExecutionControlCommand.nodeId()` (node commands; null for workflow and scheduler commands, also `RestartFromNodeCommand`), `ExecutionEvent.nodeId()` (`NodeStatusChanged`, `LogLine`, the input events, `ControlOutcome`), `ControlRequest.nodeId` (the four-argument constructor takes the command's). `DirectiveDispatcher.target(command)` returns the `CommandTarget` (session, workflow, node; the node of an `AnswerInput`/`EscalateInput` comes from its stored request of the same run).
- **Scope enforcement**: `SupervisorHost.apply` checks, after `allowedCommands` and before the rate limit (also in dry-run), that the supervisor's scope matches the command's target; otherwise `outcome=out-of-scope`, reason `target outside scope <key>`, audited as not applied. `Global` needs no lookup; a lookup error counts as outside. `SetLimitsCommand` is in scope for `Global` only.
- **Actor kind**: `SupervisorHost` writes `ControlActor.KIND_KEY = SUPERVISOR` next to the supervisor id; input authorisation reads the kind, never the actor string.
