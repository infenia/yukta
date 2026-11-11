# Supervisor framework: one owner per node, pluggable decision engines, all control through supervisors

Plan for making supervisors the only way to steer a run, and for splitting a supervisor into two clearly separate parts: the **supervisor** (what it owns, who it reports to, what it may do, how it is secured) and its **decision engine** (a plugin that decides). The rule engine, an HTTP endpoint for an external application, an outbound call and a workflow are all engines. Yukta never knows how an engine decides; it only enforces what the supervisor allows.

Status (2026-11-08): **proposal**, nothing built. The "today" statements were read from the code in the working tree on branch `fix/2043-styled-tooltips` (based on `origin/main`); each names the class it comes from. The contract items that were *to confirm* were decided in S0 (see `docs/running/supervisor-contract.mdx` and ADR 0002). The work is not filed; see [Issues](#issues). This version replaces the earlier draft of this file, which kept today's overlapping scopes and supervisor types.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Who keeps direct control of a run? | **Nobody.** Everything goes through supervisors, people included. The UI enables a control only where a supervisor allows it, and disables it otherwise. A lenient **default supervisor** exposing exactly today's endpoints carries commands for anything no supervisor owns. |
| D2 | Are starts restricted too? | **Yes**, a start needs a supervisor like any command. |
| D3 | Source of the hierarchy | **Explicit parent links.** A supervisor may have **several parents**. There is no scope relation between a supervisor and its parents. |
| D4 | First external integration | **An inbound endpoint** (the `http` engine): the external application has its own credential and operates the supervisor through it. |
| D5 | Supervisor and decision maker | **Two layers.** The supervisor is configuration and enforcement; the decision maker is an **engine plugin** behind one interface. A supervisor has exactly one engine. |
| D6 | Overlap | **One owner per node.** No supervisor may be assigned to nodes another one owns, and nothing overrides anything. A supervisor may only **delegate** to its parents. |
| D7 | Several parents | **The mode is chosen per delegation** (`ORDERED`, `ALL` or `ANY`). |
| D8 | Existing supervisor types | **Retired.** Engines replace them; `failure-guard`, `stall-watchdog` and `approval-gate` stay as ready-made **rule presets**. |
| D9 | Run-level commands (pause, stop, restart, priority, start) | **A separate run-level assignment**: each workflow has exactly one run-level owner, which may own no nodes. |
| D10 | Independent vetoes | **None.** The owner (and the parents it delegates to) is the only authority. |
| D11 | Event and non-event decisions | **Three interaction patterns**: observe, respond, initiate. Every directive carries a cause; each engine declares which signals drive it and whether it can initiate. See [Event and non-event decisions](#event-and-non-event-decisions). |
| D12 | Limits on initiative | A required `reason`, its own rate limit (default 10 per minute), an audit flag, **and a separate `initiative` allow-list** (a subset of `allowedCommands`; default none). |
| D13 | Bulk targets and hold ledger | **In contract v1**: command selectors (`owned.running`, `workflow:<id>`, `node:<ids>`) and `resume held`. |
| D14 | Timed rules | The `rules` engine **may initiate** through timed rules (`every` with `read.*`), under the same limits. |

## Decisions made while building S2 (2026-11-08)

These settle questions the plan left open. They win over the text above where they differ.

| # | Question | Decision |
|---|---|---|
| E1 | What does a legacy `Global` attachment become? | **Nothing owns "everything".** There is no `global` target. The lenient default supervisor (`operator-gateway`) covers whatever nobody owns, and `yukta.supervisor.default.enabled=false` turns it off for production: then each workflow must define its supervisor, and a workflow without one runs with no supervisor and cannot be controlled by operators. A legacy `Global` attachment is migrated to no assignment and a WARN names it; the administrator assigns it. |
| E2 | Bean and property supervisors (`rule-supervisor` via `yukta.supervisor.rules.file`, control-plane beans) | **They stop reviewing.** They own nothing, so under owner-only review they review nothing. They are replaced by engines in S4. |
| E3 | `operatorAccess` of migrated supervisors | **`NONE`**, the same as new ones. After the upgrade, a person gets `409 CONTROLLED_BY_SUPERVISOR` on a workflow that has an owner until the administrator lists commands in `operatorAccess`. |
| E4 | Groups | **Reuse `Node.group`**, the stage a node already declares (or, without one, the part of its id before the first dot). `group:<name>` selects the nodes of that group. No second `groups` map, so versions, diff, export, import, templates and the MCP guide need no change. A node has one group, so "two groups share a node" shows up as a `group:` selector and a prefix or id selector from two supervisors that take the same node. |
| E5 | UI | S8a waits for the S8 artboards (#2153) to be reviewed; the artboards are produced next, in parallel with the S2 backend. |

## Principles

1. **Yukta is neutral.** It runs no policy of its own. An engine can be a few rules, a program, an AI agent or a person at a console.
2. **The server decides what is allowed, never the engine and never the client.** Ownership, allowed commands, risk and rate are checked on the server for every directive, from every engine.
3. **One contract.** What an engine may see and do is written once (the [supervisor contract](#the-supervisor-contract-v1)), and every engine uses it.
4. **One owner, no overrides.** For any node there is at most one supervisor. This is a validation, not a priority rule.
5. **One choke point.** One class applies every control command and start. A test fails the build if another path appears.
6. **Least privilege.** A new supervisor owns nothing, sends nothing and reads only what it owns until an administrator says otherwise.
7. **Everything is audited** with who, through which supervisor and engine, over which channel.

## Architecture

```text
 ┌────────────────────────── SUPERVISOR (shell, owned by Yukta) ──────────────────────────┐
 │  id · description · enabled · dryRun                                                    │
 │  ownership   what it owns:  server | session | workflow | run-level | nodes | groups    │
 │  hierarchy   parents[] and the delegation modes (ORDERED | ALL | ANY)                   │
 │  permissions allowedCommands · canAnswer · maxRisk · operatorAccess · readNodeOutputs   │
 │  safety      rate limit · review/delegation timeouts · audit · kill switch              │
 │                                                                                         │
 │        signals in  ▼                                          ▲  directives out         │
 │  ┌────────────────────────── ENGINE INTERFACE (plugin-api) ─────────────────────────┐   │
 │  │  SupervisorEngine.run(Flux<Signal>) → Flux<Directive>   + EngineContext (read)   │   │
 │  └───────────────────────────────────────────────────────────────────────────────────┘   │
 └───────────────▲──────────────────▲─────────────────▲──────────────────▲────────────────┘
                 │                  │                 │                  │
           `rules` engine      `http` engine     `callout` engine   `workflow` engine
          (a rule language    (endpoints an      (Yukta calls an    (a Yukta workflow,
           for people)         application        endpoint)          new trigger and
                               operates)                             terminal nodes)
```

**What lives where**

| | Supervisor (shell) | Engine (plugin) |
|---|---|---|
| Owned by | Yukta core | A plugin, built into the server or reached over a transport |
| Knows about | Workflows, nodes, groups, parents, roles, keys, audit, scope, risk, rate | Only signals, directives and the read API |
| Decides | Whether a directive is **allowed** | What to **do** |
| Changes with | Yukta releases and the contract version | The engine's own version |
| Can be bypassed by an engine | No. The engine has no handle to the dispatcher, the stores or any core bean. | n/a |

## What exists today

### Reusable as is or nearly

| Need | Where it is today |
|---|---|
| Supervisors attached to a workflow or to some nodes | `SupervisorScope` (`Global`, `Session`, `Workflow`, `Section` with id or `<prefix>.*` selectors), `SupervisorAttachment` with `source`, `DefinitionSupervisors` |
| Limits on what a supervisor may do | `SupervisorCapabilities` (`allowedCommands`, `canAnswer`, `maxRisk`); `SupervisorHost.apply` (scope, rate limit, dry run) |
| Refusal and approval of commands | `ControlReviewer` (`Allowed`, `Denied`, `NeedsApproval`), `ParkedCommands`, `DefaultAnswerAuthorizer` (`separation`, `rank`, `HIGH` is human only) |
| Escalating an input request | `InputBroker`, tiers `supervisor:*`, `supervisor:above`, a human tier; `EscalateInputCommand`; `EligibleSupervisors` |
| Audit with an actor kind | `ControlActor.CONTEXT_KEY` and `KIND_KEY`, `ControlAudit` |
| A catalog with a schema | `SupervisorFactory.describe()`, `getConfigSchema()`, `/api/supervisor-types`, the UI wizard (becomes the engine catalog) |
| A rule language | `rule-supervisor`: `when`, `then`, `deny`, `requireApproval`, windows (`count`, `withinSeconds`), input `answer` and `escalate`, with a field vocabulary (`Fields`) and load-time checks |
| An outbound call with egress rules | `webhook` (`EgressPolicy`, `HostAllowList`, reply allow-list) |
| A workflow as a decision maker | `WorkflowSupervisor`, `SupervisorWorkflowRunner`, reserved slots, depth limit, internal sessions |
| Events for outside programs | `GET /api/events/stream` (SSE) |

### Wrong for the new model (replace)

| Today | Why it does not fit | Becomes |
|---|---|---|
| Several supervisors matching one node, **all** reviewing each command, any one may veto (`ControlReviewer`, `AttachedSupervisors`) | D6 and D10: one owner is the only authority | The owner reviews; nobody else |
| `CommandArbiter`, `order()`, flap windows | They exist because supervisors overlap | Removed. A per-supervisor turn-around guard stays (a supervisor flapping against itself) |
| Scope nesting by coverage (`covers`, `strictlyCovers`, `supervisor:above`) | Hierarchy is now by explicit parents | Tier `supervisor:parent`; scopes become [assignments](#assignments-and-the-one-owner-rule) |
| A supervisor type is the decision logic and the configuration at once | D5 | Supervisor plus engine |
| Types `rule-supervisor`, `rule-control-plane`, `rules-first-control-plane`, `model-control-plane`, `failure-guard`, `stall-watchdog`, `approval-gate`, `webhook`, `composite`, `workflow` | D8 | See [the mapping](#mapping-of-todays-types) |
| A key-based agent is a person for the risk rule and bypasses `allowedCommands` | An external program needs a supervisor identity | The `http` engine credential (kind `SUPERVISOR`) |
| REST, MCP and CLI reach the dispatcher with no supervisor; starts skip review | D1, D2 | The operator gateway routes to the owner |
| No definition of what an engine can read | | The read API |
| `ExecutionSupervisor` has no tick, no state, no `close()` | | `EngineContext`, `close()` |
| `UI RequirePermission` checks the key role only | D1 | `RequireControl` asks the supervision rule |

### Every direct control surface today

These are the routes D1 and D2 must bring under supervisors (found in `web`, `mcp` and `cli`; the first deliverable of S1 is the complete list).

| Surface | Entries |
|---|---|
| REST, run control (`ExecutionControlController`) | `POST /api/executions/{id}/pause`, `/resume`, `/recover`, `/priority`; node pause, resume, stop, skip, step; `POST .../nodes/{n}/retry`; `POST` and `DELETE .../nodes/{n}/breakpoint` |
| REST, stop and restart (`ExecutionController`, `WorkflowController`) | `POST /api/executions/{id}/stop`, `/restart`; `POST /api/workflow/{s}/{w}/stop` |
| REST, start | `POST /api/workflow/start` |
| REST, server-wide | `POST /api/scheduler/limits`; `POST` and `DELETE /api/admin/drain` |
| MCP | `start_workflow`, `control_workflow`, `control_node`, `set_node_breakpoint`, `set_scheduler_limits`, `rollback_workflow`; `answer_input`, `escalate_input` |
| CLI and UI | They call the REST routes above |

Gate today: `ApiKeyFilter` matches the role to the path (`ApiRole.allows`), `McpToolAccess` maps each tool to a role, then the gateway and `DirectiveDispatcher.audited` → `reviewed` → `dispatch`. Starts go to `WorkflowStarter`, outside the dispatcher.

## The supervisor (shell)

### Fields

| Field | Meaning |
|---|---|
| `id`, `description`, `enabled`, `dryRun` | As today. `dryRun` records directives and applies none. |
| `assignments` | What the supervisor owns; see below. A supervisor with none owns nothing and can still be a parent. |
| `parents` | An ordered list of supervisor ids, and `delegation`: for each kind of delegation, a mode (`ORDERED`, `ALL`, `ANY`) and a timeout. |
| `engine` | `{ type, settings }`: the one decision maker. |
| permissions | `allowedCommands` (a supervisor sends nothing until it names some), `canAnswer`, `maxRisk`, `operatorAccess` (`NONE` or the commands that people may send through it), `readNodeOutputs` (default `false`). |
| initiative | `initiative`: the commands the supervisor may send **without a triggering signal** (a subset of `allowedCommands`; default none), and `maxInitiativePerMinute` (default 10). A command caused by a tick counts as initiative. |
| safety | Rate limit, review timeout, delegation timeout, `locked`. |

### Assignments and the one-owner rule

An **assignment** says what a supervisor owns. The unit of ownership is one of: a **node** of a workflow, the **run level** of a workflow, or the **server**.

| Target | Meaning | Expands to |
|---|---|---|
| `nodes` of a workflow | Node ids, `<prefix>.*` selectors and `group:<name>` | The matching nodes |
| `run` of a workflow | Run-level commands: start, pause, resume, stop, restart, priority, and workflow-wide events | The run level of that workflow |
| `workflow` | Shorthand for `run` plus every node of the workflow | One run unit and all node units |
| `session` | Shorthand for `workflow` on every workflow of the session, now and later | As above, per workflow |
| `server` | Server-wide commands: scheduler limits, drain | The server unit |

**Rule (checked at save and again when a workflow definition is saved):** no unit may belong to two assignments. A violation is refused with `409` naming the other supervisor and the units in dispute. Specifically:

- A `workflow` assignment forbids any other assignment on that workflow, and the reverse.
- A `session` assignment forbids any assignment inside the session.
- Selectors are compared against the workflow's actual nodes (exact) and, for a selector that matches nothing yet, against each other (conservative: two prefixes overlap if one begins with the other). A new version of a workflow that makes two selectors match one node is refused at save, with the field.
- A node in several groups is fine until two of those groups go to two supervisors.
- **Nothing is inherited and nothing is overridden.** To give one node to a specialist, the main supervisor's assignment must stop covering it first.

Units that nobody owns fall to the **default supervisor** (a fallback, not an override: it never applies where an owner exists). With `yukta.supervisor.default.enabled=false`, an unowned unit cannot be controlled by anyone.

**Groups.** The workflow definition gains `groups`: a map of a name to a list of node ids and `<prefix>.*` selectors, validated when the definition is saved (each member resolves). An assignment names a group as `group:<name>`.

**Definitions.** The `supervisors` block in a session or workflow definition becomes an `assignments` block with the same reconcile-on-save behaviour as today (`DefinitionSupervisors`, source `DEFINITION`, deterministic ids); the exclusivity rule runs before anything is attached. A stored definition needs no migration: `supervisors` is read as `assignments` (same entries), so its assignments keep source `DEFINITION` and the same ids, and the next save writes `assignments`. One that overlaps another owner after the upgrade is disabled as a conflict at start-up, for the administrator to resolve.

### Hierarchy and delegation

- A supervisor lists `parents` (zero or more). Rules at save: each parent exists, no cycle, no self, depth at most 4. A parent needs no assignment and no scope relation: it can be a pure decision supervisor (for example a person at a console, or an AI that only approves).
- A parent **never sends commands for its children's units**. It answers what is delegated to it. Ownership is not shared, so nothing is overridden.
- Three kinds of **delegation** in v1:

| Kind | A child delegates when | A parent answers with |
|---|---|---|
| `APPROVAL` | One of its own commands needs approval (`requireApproval`, or a rule `delegate`) | allow or deny |
| `INPUT` | It escalates an input request | an answer, or its own escalation |
| `CONSULT` | Its engine asks a question | a typed answer (free text or a choice) |

- **Modes (D7), set per kind in `delegation`:**
  - `ORDERED` (default): ask the parents in order. The first answer decides. A parent that times out or has no opinion passes it to the next.
  - `ALL`: ask all at once and wait for every answer (or its timeout). One refusal refuses; it is allowed only when every parent allows; otherwise a person decides.
  - `ANY`: ask all at once; the **first decisive answer** (allow or refuse) decides and the others are not waited for. A refusal that arrives after an allow does not count, so choose `ALL` when every parent must be able to refuse.
  - A **refusal that decides is final**: no later parent or person overturns it. When no parent decides (none configured, all silent, or the chain is exhausted) the request goes to the **person tier** (the input inbox), as an unresolved escalation does today.
  - The modes decide `APPROVAL` (allow or deny). An `INPUT` escalation follows its route instead: the tier `supervisor:parent` targets the parents, and the tier's own `mode`, `quorum` and `timeout` decide, with the votes of a `QUORUM` or `UNANIMOUS` tier; `delegation.INPUT` is not read. A `CONSULT` asks the parents in its mode and gives the child **every answer** (`Signal.Consulted(key, answers by parent)`); the child's engine chooses between different answers, and no answer is an empty map.
- `DefaultAnswerAuthorizer` ranks a parent above its child whatever their assignments, and keeps `separation` and "`HIGH` is for a person".

## The engine interface (the decision maker)

One interface in `plugin-api`. An engine is a plugin like a processor: it declares a descriptor and a settings schema, and the supervisor holds its settings.

```java
interface SupervisorEngineFactory {                   // found like plugins: a Spring bean
  String type();                                      // "rules", "http", "callout", "workflow"
  EngineDescriptor describe();                        // name, description, category, icon, example, drivers
  List<ConfigField> getConfigSchema();                // the settings, same ConfigField as plugins
  void validate(EngineConfig config);                 // at save, never leaks a secret
  SupervisorEngine create(EngineConfig config, EngineContext context);
}

interface SupervisorEngine extends AutoCloseable {
  Flux<Directive> run(Flux<Signal> signals);          // signals in, directives out
}
```

**Signals** (what the engine receives; everything is restricted to what the supervisor owns):

| Signal | Content |
|---|---|
| `Event` | An execution event: node status, workflow status, control outcome, log line, input events, `EventsMissed` |
| `Tick` | Time, only if the engine's settings ask for a period |
| `Review` | A command that a person or the operator gateway wants to send for a unit the supervisor owns; the engine answers it |
| `Delegated` | A delegation from a child: kind, payload, id; the engine answers it |
| `Started` | The engine started or reconnected: the reason (`first`, `resubscribe`, `credential-rotated`) and a snapshot with a cursor (owned executions, open inputs, held commands) |

**Directives** (what the engine may return; each is checked by the supervisor before it has any effect):

| Directive | Meaning |
|---|---|
| `Command` | A control command (the Act table) with a [cause](#event-and-non-event-decisions) and, for initiative, a `reason`; its target may be a selector; it may carry `ifStatus`. Checked against ownership, `allowedCommands`, the `initiative` list, rate, risk, dry run |
| `ReviewVerdict` | `allow`, `deny`, `requireApproval` for a `Review` |
| `DelegationAnswer` | The answer to a `Delegated` signal |
| `Delegate` | Pass a question up to the parents, with a kind and a payload |
| `Notify` | An audited message that changes nothing (in v1, decided in S0) |
| `NoOpinion` | Explicitly no answer |

**`EngineContext`** (all an engine can reach): `self()`, the read API (`queries()`), a per-supervisor `state()` store, a logger tagged with the supervisor, and `ticks(period)`. It has no handle to the dispatcher, the gateway, the stores or any core bean. In-JVM engines are plugin modules that may depend on `plugin-api` only; an ArchUnit rule in the `architecture` module enforces it.

**What the supervisor enforces on every directive:** the target is a unit it owns (run-level commands only against its run units, node commands only against its nodes, server commands only if it owns the server unit); the command is in `allowedCommands`; the rate limit; risk limits for answers; dry run; the supervisor is enabled. A rejected directive is counted, recorded with a reason and never reaches the engine as an outcome. Failures of an engine resubscribe with backoff and never touch a run. Reviews and delegations have timeouts; a stop is allowed when a review times out, as today.

## Event and non-event decisions

A supervisor decides in three ways. The contract carries all three on one duplex stream (signals in, directives out), and a directive is never tied to a signal by the stream itself.

| Pattern | Input | Example | Style |
|---|---|---|---|
| **Observe** | A workflow event | A node failed three times, so pause the run | Edge-triggered: something happened |
| **Respond** | A request with a deadline: a person's command to review, an input request, a child's delegation | "Approve this stop?" | Edge-triggered, correlated to one request |
| **Initiate** | Nothing, or a timer | The CPU is high, so pause my runs | Level-triggered: look at the state, then act |

Event-driven supervisors are edge-triggered. Most proactive ones are level-triggered, so they must not depend on seeing every event; they use the snapshot, the tick and the read API.

**Cause.** Every directive carries a cause, stamped by the server and written to the audit: `event:<id>` (a reaction to an event), `answers:<id>` (the answer to a `Review` or `Delegated` request), `tick` (a timer) or `initiative` (anything else). An `answers:` directive must match an open request and is accepted once; a late, unknown or second answer is refused. A command caused by `tick` or `initiative` needs a short `reason`, which the audit and the UI show ("paused by agent X, no event: cpu above 90%").

**Drivers.** An engine's descriptor declares which signals drive it and whether it can initiate. The supervisor refuses what the descriptor does not declare.

| Engine | Takes | Initiates |
|---|---|---|
| `rules` | events, ticks, reviews, delegations | Through timed rules (`every` with `read.*`) |
| `workflow` | the same, as signal kinds that start a run | A run started by a tick |
| `http` | everything | At any time, by `POST /directives` |
| `callout` | events, reviews, delegations | No: Yukta makes each call, so it can only reply to it |

**Limits on initiative (D12).** Initiative is the riskiest case because no signal justifies it, so it has its own limits on top of ownership and `allowedCommands`: the commands must be in the supervisor's `initiative` list (default none, so a new `http` supervisor can observe and answer but not act on its own until an administrator names commands), a `reason` is required, `maxInitiativePerMinute` applies, dry run applies, and the audit and the UI flag it.

**Targets without an event (D13).** A reaction knows its execution id; an initiative does not, and listing then acting races with the runs. A command may name its target as `execution:<id>`, `workflow:<id>`, `node:<ids>` or `owned.running`. The server expands a selector to the owned executions it matches (at most 200), applies and audits each command separately as the directive `<key>#<execution>`. The submission only says it was accepted; the result per target is recorded in the decisions (`GET /api/supervisors/decisions`, the Activity tab groups them by key), so a partial success is visible without the engine receiving its own outcomes (see "A loop of supervisors").

**Hold ledger (D13).** The server records which executions this supervisor paused. An entry ends as soon as **anyone** resumes or stops that execution, or the execution ends, so a later pause by a person or another owner never inherits it. `resume held` resumes only the entries still there. It is kept in memory on purpose: a restart marks unfinished runs `ABORTED`, and a resumed run is not paused, so no hold can outlive a restart.

**Preconditions and outcomes.** A command may say `ifStatus` (for example `RUNNING`), so a decision based on a read that has gone stale fails safely. Outcomes are explicit: `APPLIED`, `NOT_FOUND`, `NOT_OWNED`, `PRECONDITION_FAILED`, `ALREADY_IN_STATE`. Commands are idempotent in their end state. `AnswerInput` already has `expectedVersion`; this generalises it.

**Start and resync.** When an engine starts or reconnects it receives `Started` with a snapshot and a cursor, then the events after the cursor. `EventsMissed` stays for gaps that a cursor cannot close. A level-triggered engine therefore never depends on one missed event.

**Operator commands and testing.** A person's command is a `Respond`, not an initiative: it reaches the owner as a `Review`. An administrator may send synthetic signals in dry run only, to test an engine; the rules replay uses the same path.

**Ordering.** Directives from one supervisor are applied in order per target. The turn-around guard catches a pause and resume loop. A reaction and an initiative from the same supervisor get exactly the same checks.

## The engines

Each engine is segregated from the others and from the supervisor: it has its own descriptor, schema, documentation page and tests, and it depends on `plugin-api` only.

### `rules`: a rule language for people who do not know Yukta's internals

An engine whose settings are a **rule document**. The rule author sees a small stable vocabulary, never a Java class.

- **Rule document v1** (`schemaVersion: 1`): each rule has an `id`, a `when` (an event kind, optional filters, an optional window `count` within `seconds`, or `every: <period>`), and a `then` (one or more of the actions below). It is the format of `rule-supervisor` today (`when`, `then`, `deny`, `requireApproval`, input `answer` and `escalate`), cleaned up and versioned; the current files are read as v1.
- **Facts** are a documented namespace (`event.kind`, `event.node`, `event.status`, `run.id`, `run.workflow`, `node.id`, `log.level`, `input.risk`, ...), not raw maps: today's rules reach into `metadata` and a SpEL expression, which exposes internals. Advanced SpEL `if` stays behind a flag, over the same facts, in a read-only sandbox.
- **Read access:** a rule may use `read.executions(status)`, `read.execution(id)`, `read.inputs()`, bounded and limited to what the supervisor owns, so a rule can say "if more than 3 runs are RUNNING".
- **Timed rules** (`every: 30s`) run on a tick and, with `read.*`, make the rules engine level-triggered (D14). They are initiative: they need the actions in the supervisor's `initiative` list, a `reason`, and count against `maxInitiativePerMinute`.
- **Actions** use plain names for the Act table (`pause run`, `resume run`, `stop node`, `skip node`, `start workflow`, `answer`, `delegate`, `notify`, `require approval`, `deny`). An action outside `allowedCommands` is refused and counted, as today.
- **Dynamic:** rules are edited at run time, with no restart. `PUT /api/supervisors/{id}/engine/rules` validates, keeps a version (newest 20), swaps the rule set atomically and audits it. `GET .../versions` and a rollback. `POST .../replay` runs a draft over a past execution's events in dry run and lists what it would have done.
- **Presets** (D8): `failure-guard`, `stall-watchdog` and `approval-gate` become templates that fill in a rule document with a few parameters (the failure guard already compiles to one rule in `FailureGuardFactory`; the watchdog uses `every`).
- The UI gets a rule form so that a common rule needs no JSON (a later PR).

### `http`: an endpoint that an external application operates

The engine that makes a supervisor operable by any program, deterministic or an AI. It is **inbound**: the application connects to Yukta.

- A credential per supervisor, shown once, stored as a hash, rotated and revoked at any time, optional expiry and source address list. It works only on `/api/agent/v1/**`, never on a human endpoint, and no human key works there. Its actor kind is `SUPERVISOR`, so it is never a person for the risk rule.
- Endpoints: `GET /self`; `GET /events` (SSE of the signals, with `EventsMissed` on a gap); the read endpoints (the read API); `POST /directives` (the directives above, one typed body, with an idempotency key); `POST /reviews/{id}` and `POST /delegations/{id}` to answer a `Review` or `Delegated` signal within the timeout.
- Non-event decisions: `POST /directives` may be sent at any time (cause `initiative`, with a `reason`, a selector target and an optional `ifStatus`). `GET /snapshot` returns the owned state with a cursor and `GET /events?after=<cursor>` resumes after it, so a reconnecting program never depends on a missed event.
- Everything a program sends is a directive, so the supervisor checks it exactly as it checks any engine's. A leaked credential is limited by ownership, `allowedCommands`, the rate limit and `dryRun`, and cut at once by disabling the supervisor.
- Limits per credential: requests per second, open streams (default 2), open reviews, request size. Stable errors: `401`, `403`, `409`, `429`.
- OpenAPI for the agent API; CLI `yukta supervisors credential create|rotate|revoke`; later the same operations as MCP tools behind the supervisor credential.

### `callout`: Yukta calls an endpoint (replaces `webhook`)

For a simple integration that does not want to keep a connection. The server posts each signal (secrets masked) and reads directives from the reply, through the existing egress policy (allow-list, no private addresses, no redirects). It uses the same directive schema as the `http` engine and has no read access; an integration that needs to read uses the `http` engine. It is reactive only: Yukta makes each call, so it cannot initiate, and a reply may carry directives only for the signal it answers.

### `workflow`: a Yukta workflow as the decision maker *(proposal, to be evaluated)*

Replaces the `workflow` supervisor type and its ad hoc result convention. Three new nodes in plugin modules:

| Node | Kind | Purpose |
|---|---|---|
| `SUPERVISOR_SIGNAL` | Trigger | The run's entry. Emits the signal (secrets masked) with the supervisor id. It declares which signal kinds start the workflow. |
| `SUPERVISOR_DECISION` | Terminal | Ends the run with a typed list of directives (or `NoOpinion`). Validated against the directive schema when the workflow is saved and again by the supervisor at run time. |
| `SUPERVISOR_READ` | Processor | Calls the read API for the owning supervisor, so a workflow need not use a key with `MCP_TOOL_CALL`. |

- A workflow used as an engine must have exactly one `SUPERVISOR_SIGNAL`, at least one `SUPERVISOR_DECISION`, and live in an internal session; checked at save.
- The signal kinds that start it (`Event`, `Tick`, `Review`, `Delegated`, `Started`) are listed in the engine's settings. A run started by a `Tick` is how this engine initiates.
- It keeps what the supervisor workflow has today: a run per signal with the signal as input, `maxConcurrent`, a timeout, reserved slots outside the normal pool, the depth limit, and no signals from its own session.
- A workflow can still call any HTTP endpoint, including Yukta's own REST API with a key. Workflows are written by administrators, so this is a trusted-author boundary, not something the engine can prevent; the documentation says so.
- **Spike result (S9):** it adds something only in two places: the decision logic is a workflow people can read and edit in the UI, and a `SUPERVISOR_DECISION` that fails the run on a command the contract cannot read (the old convention dropped it silently). So the engine stays, as a thin layer on what exists: type `workflow` is the engine; `SUPERVISOR_SIGNAL` (trigger, emits the signal) and `SUPERVISOR_DECISION` (terminal, `OUTCOME_TERMINAL` plus validation) are added; `SUPERVISOR_READ` is not built, because a workflow can call the REST API or the agent API and `rules` already has `read.*`. The save-time rule (exactly one `SUPERVISOR_SIGNAL`, at least one `SUPERVISOR_DECISION`) is not enforced yet, so a workflow with `API_TRIGGER` and `OUTCOME_TERMINAL` still works. The original question was: whether this adds anything over `rules` plus `http` and a parent that is an AI. The advantage is that the decision logic is visible and editable in the Yukta UI like any workflow; the cost is three nodes and a second way to run supervisor logic. Start with a spike.

### `operator-gateway`: the default supervisor's engine

The built-in engine of the **default supervisor**: the legacy REST, MCP, CLI and UI endpoints, unchanged, as the way a person acts. It carries a command only for a unit that has no owner. It is the only engine that accepts human keys, and the role and session checks of `ApiKeyFilter` still run first.

### Later

`model` (a decision model through the existing `DecisionModel`s), and a `process` engine (the contract as JSON lines on stdin and stdout, reusing `yukta.process.allowed-commands`).

## Channels and authority

Every command or start arrives on one channel; the owner decides.

| Channel | Rule |
|---|---|
| `ENGINE` (a directive from a supervisor's engine, from any engine) | Allowed only for units the supervisor owns, and only the commands it may send. |
| `OPERATOR` (REST, MCP, CLI, UI) | The role and session check first. Then the owner of the target unit: if it lists the command in `operatorAccess`, the command goes to its engine as a `Review` and the verdict applies; if not, `409 CONTROLLED_BY_SUPERVISOR` naming it. A unit with no owner goes to the default supervisor. |
| `SYSTEM` (shutdown, timeouts, a parent run cancelling its children, recovery) | Exempt, audited as `SYSTEM`. The list of these callers is an S1 deliverable. |
| `DELEGATION` | Not a channel for commands: a parent only answers. |

A start has a target (the workflow), so it is owned by the **run-level owner** of that workflow and goes through the same rule (`StartWorkflowCommand`).

## The supervisor contract (v1)

The contract is what an engine may see and do, and the compatibility promise: additive changes only within a major version. It lives in `docs/running/supervisor-contract.mdx` and as `SupervisorContract` in `plugin-api`; `GET /api/supervisor-contract` returns the version and the operations.

**Read** (limited to what the supervisor owns, redacted, paged):

| Operation | Returns |
|---|---|
| `self` | id, owned units, parents, permissions, contract version |
| `events` | The signal stream, `EventsMissed` on a gap |
| `snapshot` | Owned executions with status, open inputs, the hold ledger and a cursor, for `Started` and for a level-triggered engine |
| `executions` | Executions of owned workflows: id, workflow, status, priority, start time |
| `execution` | One execution with its nodes' statuses |
| `workflow` | Definition summary: node ids, types, edges, groups; no config values |
| `inputs` | Open input requests of owned units |
| `logs` | A bounded, redacted tail of an execution's log |
| `scheduler` | Limits and queue depth (server owner only) |

Node outputs and message payloads are **not** readable unless `readNodeOutputs` is set, and then only through the redaction the input requests already use.

**Act** (always checked against ownership, `allowedCommands`, rate, risk, dry run):

| Group | Operations |
|---|---|
| Run level | `start` (new `StartWorkflowCommand`), pause, resume, stop (drain or immediate), restart, restart from a node, priority |
| Targets | `execution:<id>`, `workflow:<id>`, `node:<ids>` or `owned.running`; a selector is expanded by the server to the owned executions it matches (at most 200), each applied and audited separately; the submission is accepted, and the result per target is in the decisions. `resume held` resumes only what this supervisor paused. A command may carry `ifStatus`. |
| Node level | pause, resume, stop, skip, retry, step mode, breakpoints |
| Inputs | answer, escalate |
| Server | set limits, drain (server owner only) |
| Other | `notify`, `delegate` |

**Review and delegation:** the answer to a `Review` or `Delegated` signal is `allow`, `deny` or `requireApproval` (or the typed answer), within `yukta.supervisor.review-timeout` (5 s) or the delegation timeout. A refusal is final.

**Guarantees:** delivery of events is best effort with a visible gap; commands for one run are applied in order; every directive gets an outcome and a reason; an engine never sees the outcomes of its own commands; a rejected directive says why.

## Security design

| Threat | Control |
|---|---|
| A stolen human key stops runs | Its commands go through the owner, which may refuse operator access. Role and session limits stay. |
| An external program acts with a person's rights | The `http` credential is accepted only on the agent API, `/api/agent/v1/**` (REST and its MCP form `/api/agent/v1/mcp`, with the same per-credential limits); its actor kind is `SUPERVISOR`. |
| A credential leaks | Per supervisor, shown once, hashed, rotated, revoked, optional expiry and address list. Ownership and `allowedCommands` limit the damage. Disabling the supervisor cuts it at once. |
| An engine acts outside what its supervisor owns | Ownership is checked on the server from the command's real target, never from what the engine says. A lookup error counts as not owned. |
| Two supervisors fight or override each other | The one-owner rule makes it impossible, and is re-checked at every definition save. |
| An administrator removes the supervisor that protects a workflow | Creating, changing, assigning and detaching supervisors is `ADMIN`, audited under run id `supervisors`. `locked`: only a parent or an administrator holding the lock permission may detach it (decided in S0: `locked` is in, built in S2). |
| Data leaks through the read API | Limited to owned units, paged, redacted, node outputs off by default, reads counted per supervisor. |
| An engine overloads the server | Rate limit per supervisor, open streams and reviews capped, bounded buffers with `EventsMissed`. |
| A loop of supervisors | A supervisor never sees its own outcomes (not even per target: those are in the decisions, for people and clients); internal sessions are hidden; delegation depth at most 4; workflow-engine depth limit. |
| A new endpoint skips the checks | One choke point (`SupervisedControl`) and an ArchUnit rule in the `architecture` module: only it may call the gateway's executor and `WorkflowStarter`; engines may depend on `plugin-api` only. |
| A reviewer that hangs or crashes | Timeouts; an error denies; a stop is still allowed. |
| Replay and duplicates | An idempotency key on every directive; starts already have one. |
| An engine acts without any trigger | Initiative needs its own allow-list (default none), a reason, its own rate limit and dry run, and is flagged in the audit and the UI. |
| Loss of independent vetoes (D10) | Accepted. The owner and its delegation chain are the only authority; an administrator can always detach. |

## Mapping of today's types

| Today | Becomes |
|---|---|
| `rule-supervisor`, `rule-control-plane` | Engine `rules` (the rule files are read as v1) |
| `rules-first-control-plane` | A `rules` supervisor whose fallback `delegate`s a `CONSULT` to a parent with a model engine |
| `model-control-plane` | Later: engine `model`; or a parent whose engine is a `workflow` with an `LLM_CALL` node |
| `failure-guard`, `stall-watchdog`, `approval-gate` | Presets of the `rules` engine |
| `webhook` | Engine `callout` |
| `composite` | Removed. Its review `ALL` and `ANY` become the delegation modes of `parents`. **`MAJORITY` has no equivalent**: a stored composite is kept, does not start and is named in a start-up WARN; the administrator rebuilds it with `parents` (`ALL` or `ANY`), or waits for `QUORUM` if it is added |
| `workflow` | Engine `workflow` with the three new nodes |
| `CommandArbiter`, `order()`, `ControlReviewer` fan-out | Removed; a turn-around guard stays per supervisor |
| `SupervisorScope`, `strictlyCovers`, `supervisor:above` | Assignments and units; tier `supervisor:parent` |
| `SupervisorRef` (`supervisors` in definitions) | `assignments` in definitions |

**Migration** (alpha, so breaking changes are accepted): a Flyway migration (next free number) adds the engine columns and `parents`, converts a type's settings into the engine's, converts each attachment into assignments, and flags attachments that now overlap. Overlaps from old data are never merged silently: at start-up the first attachment per unit (oldest first) stays enabled, the others are disabled with a visible `conflict` status and a warning, and the administrator resolves them. Examples (`examples/16`, `examples/17`, `examples/dogfood/supervisor`), the `boot` acceptance tests, the docs, the CLI `supervisors` command, the MCP tools and `ui/src/features/supervisors` move with the phases that change them.

## UI and UX design

Revised 2026-11-09 after the first artboards were rejected; the approved artboards are `design/plans/supervisor-framework/artboards/` (#2153, PR #2206). This section replaces the earlier wizard-first design.

### UX principles

1. **Viewing first.** An AI agent usually configures supervisors over MCP, the CLI or the API. People mostly want to see what was configured: which supervisor owns which workflow and node, the hierarchy, what each may do, and what each did. Creating and editing is a secondary action that sits next to the matching MCP and CLI commands.
2. **Decision makers are integrations, shown like Plugins.** A supervisor is wired to one decision maker (Rules, External system, Program on this server, Workflow); the catalog uses the Plugins page layout.
3. **Built from the running app.** The left icon rail, the header with search, `PageHeader` with tabs on the right, the Plugins master-detail layout, `StateMessage`, `Card`, `TableFrame`, `CodeBlock`. No new visual language.
4. **Say who is in charge.** Every run, workflow and node shows its owner; a disabled control says why, names the supervisor and links to it, as text and not only in a tooltip.
5. **Plain words.** Supervisor, decision maker, owns, reports to, acts on its own, people can. Engine, unit, directive and delegation only in docs and JSON.
6. **Accessible and responsive.** Visible focus, `aria-disabled` with the reason as text, an icon plus a word for every status, the app's reduced-motion guard, the 390 px layout with the bottom tab bar.

### Vocabulary in the UI

| Concept | UI word |
|---|---|
| Supervisor | Supervisor |
| Engine | Decision maker: **Rules** (`rules`), **External system** (`http`), **Program on this server** (`process`), **Workflow** (`workflow`) |
| Catalog category | Rules, External system, On this server |
| Assignment | Owns |
| Parents | Reports to |
| `initiative` | Acts on its own |
| `operatorAccess` | People can… |
| Default supervisor | Direct control |

### Information architecture

| Place | What it shows |
|---|---|
| `/supervisors` tabs | **Supervisors** (master-detail), **Decision makers** (catalog), **Activity** |
| Supervisors, left | Filter; **Hierarchy** (indented tree; a supervisor with several parents appears under each, "also under …") or **By workflow** (workflows with their supervisors, what nobody owns, the server) |
| Supervisors, right | Header (decision maker link, running or dry run, version), description, an "at a glance" strip (reports to, the supervisor, owns), tabs **Owns**, **Allowed operations**, **Hierarchy**, **Settings**, **History**, **Activity**, and "Change it from your agent" (MCP, CLI, API) |
| Owns | Per workflow: every node with its owner, the selector, the run-level owner. View only; ownership is changed by agents and definitions (`yukta supervisors assign`, MCP, `assignments` in a definition) |
| Allowed operations | Per command group: it may send, acts on its own, people can through it (`allowedCommands`, `initiative`, `operatorAccess`) |
| Settings | Rules: the rules as sentences, replay on a past run, the document as JSON. External system: out (url), in (agent API), decisions; the settings; the credential (create, copy once, rotate, revoke, last used). Others: the schema form, read only |
| History | The kept versions with their time; compare (client-side JSON diff); restore (rollback). Who saved a version is not recorded |
| Decision makers | Plugins layout: categories, the decision maker, "how it connects", tabs Signals, Settings, Presets (Rules), Example, Used by |
| Activity | Cause (event, answered, tick, on its own), supervisor, target, command, outcome, reason; per-target results; filters |
| Workflow page | A **Supervision** tab: nodes with their owner (colour plus the name), the owners table, who can stop the run; the run controls gated with the reason |
| Conflicts | A banner on `/supervisors` and on an affected workflow when start-up disabled overlapping assignments; the review lists each conflict with "Keep this one" |
| Input inbox | Each escalation shows its approval chain ("asked build-agent, then release-lead, now you") |
| Control page, palette | The supervisors tile adds unowned workflows and conflicts; palette entries "Go to supervisor", "Who owns this workflow?" |
| Workflow editor | A groups editor (group name to node ids and prefixes) with the server's field errors |
| Edit | A plain form (description, decision maker, dry run, reports to, settings as JSON) for a quick change by hand; there is no wizard and no assign dialog |

### States, copy and errors

Every place has loading, empty, error and out-of-date states built from `StateMessage`. Refusals use the same words everywhere (UI, CLI, MCP):

| Code | Text |
|---|---|
| `409 CONTROLLED_BY_SUPERVISOR` | "*name* controls this. People cannot do this directly." |
| `409 OWNERSHIP_CONFLICT` | "*units* are already owned by *name*." |
| `202 AWAITING_APPROVAL` | "Waiting for approval by *name*." |
| `403 CONTROL_REJECTED` | "*name* refused: *reason*." |
| `409 NOT_OWNED` | "*name* does not own this." |

A disabled control says "Controlled by *name*. Open *name*", "No supervisor can do this here" or "Needs OPERATOR".

### Implementation map

| Phase | Screens | Backend it needs |
|---|---|---|
| S8a (#2154) | Page shell and tabs; Supervisors tab (hierarchy and by-workflow, header, at a glance, Owns, Allowed operations, Hierarchy); `RequireControl` over `GET /api/supervision` with the `RequirePermission` fallback; workflow Supervision tab; conflicts banner and review; delete `CoverageTab`, `AttachmentsTab`, `AttachDialog`, `EffectiveSupervision`, `HowItWorks`, `SupervisorWizardPage` | exists |
| S8b (#2155) | Decision makers tab (shared with the Plugins page pieces); Settings (generic); History; the Edit form; the groups editor | catalog `signals` and `canInitiate` |
| S8c (#2156) | Rules settings (sentences, replay, JSON); External system settings and the credential panel (secret shown once, absent from the DOM and storage afterwards) | exists |
| S8d (#2157) | Activity; approval chain in the inbox; palette entries; Control tile counts; states pass; Playwright scenarios with screenshots | decisions with `cause`, `key`, `nodeId` and the directive's reason |

The UI reads `GET /api/supervision`; an older server without it falls back to `RequirePermission`.

## Clients: MCP, CLI, REST and others

Two audiences use these interfaces, and they are kept apart:

- **Operators** (people and agents that start, stop and watch runs): their calls keep the same paths, tools and commands; what changes is that a refusal now names the supervisor.
- **Supervisor clients** (programs and AI agents that **are** a supervisor's decision maker): they use a separate surface with the supervisor's own credential, which exposes the contract and nothing else.

### Compatibility for existing clients

| Client | Change | Breaking? |
|---|---|---|
| REST control and start routes | Same paths and bodies. New refusals `409 CONTROLLED_BY_SUPERVISOR` and `202 AWAITING_APPROVAL` appear only where an owner exists. Responses gain `via` (the supervisor) and `cause`. | No while nothing is owned (the default supervisor allows what is allowed today) |
| MCP operator tools (`start_workflow`, `control_workflow`, `control_node`, `set_node_breakpoint`, `set_scheduler_limits`, `answer_input`, ...) | Same names and arguments. The result object gains `via` and, for a refusal, `code`, `supervisor` and `reason`. Descriptions say that a supervisor may refuse. | No |
| CLI control commands | Same commands. A refusal prints the owner and the reason and exits with a distinct code (`6`). | No |
| `GET /api/supervisor-types`, `attach_supervisor`, `detach_supervisor`, `yukta supervisors types`, the `supervisors` block of definitions | Replaced by engines, assignments and the `assignments` block | **Yes** (alpha, announced in the migration note) |
| Dogfood `yk` and `examples/dogfood/supervisor/*.json`, `examples/16`, `examples/17` | Migrated with S7 | Yes, same PR |

Discovery: `GET /api/supervisor-contract` returns the contract version; every agent-API response carries `Yukta-Supervisor-Contract: 1`; the `whoami` MCP tool and `GET /api/me` gain a `supervision` summary (the default supervisor is on or off, the number of owned workflows the caller can reach).

### REST and OpenAPI

New or changed groups (all in `/api`, documented in `docs/api/` and in the OpenAPI snapshots):

| Group | Endpoints |
|---|---|
| Supervision (read, any key) | `GET /supervision?session=&workflow=&execution=&node=`, `GET /ownership?session=&workflow=`, `GET /supervisor-contract` |
| Decision makers | `GET /supervisor-engines`, `GET /supervisor-engines/{type}` (replace `supervisor-types`) |
| Supervisors (admin) | `GET/POST /supervisors`, `GET/PUT/DELETE /supervisors/{id}` (new shape: assignments, parents, engine, permissions), `POST /supervisors/validate` (whole draft, including overlap), `GET /supervisors/{id}/assignments`, `PUT /supervisors/{id}/assignments` |
| Rules | `GET/PUT /supervisors/{id}/engine/rules`, `GET .../versions`, `POST .../rollback`, `POST .../replay` |
| Credentials | `POST /supervisors/{id}/credentials` (returns the secret once), `DELETE`, `GET` (metadata only) |
| Activity | `GET /supervisors/decisions` (adds `cause`, `reason`, target results) |
| Agent API (supervisor credential only) | `/api/agent/v1/**` as in the `http` engine |

The OpenAPI snapshots (`ui/openapi.json`, `docs/api/openapi.json`) and `ui/src/types/api.generated.ts` are regenerated with the project task; the agent API has its own OpenAPI document so that a client for it can be generated.

### MCP

**Operator tools** keep their names; their results and descriptions change as above, and the tool descriptions and the `yukta-workflows` skill (in `skills/`, installed as described in `docs/interfaces/mcp.mdx`) tell an agent: call `whoami` first; a refusal naming a supervisor is a decision, not an error to retry; ask the user before asking a supervisor owner to change. The role map in `McpToolAccess` does not change for these.

**Management tools** (replace the `*_supervisor` tools; `ADMIN` unless stated):

| Tool | Role | Purpose |
|---|---|---|
| `list_supervisor_engines`, `get_supervisor_engine` | `VIEWER` | The catalog with the settings schema, the drivers and an example |
| `list_supervisors`, `get_supervisor` | `VIEWER` | Supervisors, with owned units, parents and the effective ownership |
| `get_ownership` | `VIEWER` | Who owns each node and run level of a workflow |
| `check_assignment` | `ADMIN` | Validate an assignment (overlap, groups) without saving |
| `save_supervisor`, `set_assignments`, `delete_supervisor` | `ADMIN` | Create or change; the assignment step is separate so an agent can check first |
| `save_rules`, `replay_rules` | `ADMIN` | Edit a rules supervisor's rules, and test them on a past run |
| `create_credential`, `rotate_credential`, `revoke_credential` | `ADMIN` | Returns the secret once; the description warns that it passes through the model |
| `get_supervision` | `VIEWER` | The capability answer for a target (the same as `GET /api/supervision`) |

Constraints that apply as before: list results are wrapper records (a `Flux` result is cut to its first element, and `Mono<List<T>>` with an output schema fails at start-up with Spring AI 2.0); the detail tool includes the settings schema (`get_plugin_details` still lacks it, do not copy that gap).

**Supervisor MCP endpoint (for an AI agent that is a supervisor).** A second endpoint, `/api/agent/v1/mcp`, inside the agent API, so it is guarded like the rest of `/api/agent/v1/**`: only a supervisor credential works there (a human key is refused, and a supervisor credential is refused on `/mcp` and everywhere else), with the same per-credential limits (`yukta.agent.*`). It lists only supervisor tools, so an agent connected to it cannot see or call anything else. MCP has no server push that a model can wait on, so the stream becomes a poll with a cursor.

| Tool | Purpose |
|---|---|
| `supervisor_self` | Identity, owned units, parents, permissions (including the `initiative` list), contract version |
| `supervisor_snapshot` | The owned state with a cursor |
| `supervisor_next_signals` | Signals after a cursor, with an optional wait of up to 25 s (long poll); returns `EventsMissed` on a gap |
| `supervisor_list_executions`, `supervisor_get_execution`, `supervisor_get_workflow`, `supervisor_list_inputs`, `supervisor_get_logs` | The read API |
| `supervisor_send_directive` | A directive with `cause`, `reason`, a selector target and `ifStatus` |
| `supervisor_answer_review`, `supervisor_answer_delegation`, `supervisor_delegate` | Answer or pass up |

A new skill `skills/yukta-supervisor` teaches an agent the loop (snapshot, then poll, decide, send with a reason), the limits (a refusal is final, `initiative` needs a reason and an allow-list entry, dry run first), and the safe defaults. The same two endpoints serve any MCP client, so the Claude Code, Gemini CLI and other installs in `docs/interfaces/mcp.mdx` apply unchanged.

### CLI (`cli/`, Go, cobra)

`yukta supervisors` is read only today (`status`, `types`, `list`, `show`). It becomes the management client:

| Command | Purpose |
|---|---|
| `yukta supervisors engines [type]`, `list`, `show <id>`, `status` | Read |
| `yukta supervisors save -f supervisor.json`, `delete <id>`, `validate -f …` | Create, change, check |
| `yukta supervisors assign <id> --workflow w --nodes 'build.*' [--group g] [--run-level]`, `unassign`, `check-assign …` | Ownership |
| `yukta supervisors parents set <id> --parent a --parent b --mode approval=ordered` | Hierarchy |
| `yukta supervisors rules get\|set\|versions\|rollback\|replay <id>` | Rules |
| `yukta supervisors credential create\|rotate\|revoke <id>` | The secret is printed once, to stdout only |
| `yukta supervisors decisions [--supervisor --cause initiative]` | Activity |
| `yukta ownership [--session s --workflow w]` and `yukta supervision …` | Who owns what, and whether a command would be allowed |
| `yukta supervisor-contract` | The version and operations |

Existing commands (`workflow stop`, `node pause`, `executions …`, `scheduler`) keep their syntax and print the owner and reason on a refusal (exit code `6`, decided in S0; 4 is the server-error code). A reference agent, `yukta supervisor agent --credential-file … --exec ./decide.sh`, bridges the agent API to a local program (signals as JSON lines on its stdin, directives read from its stdout), so a script in any language can be a supervisor without writing an HTTP client.

### Other clients and assets

| Asset | Change |
|---|---|
| Bruno collections (`bruno/`) | Add `SupervisorController` and `AgentApi` folders with the new requests and a credential environment variable |
| Examples | Replace `examples/16` and `examples/17`; add a **CPU guard** example (an external program using the agent API and `initiative` with `resume held`), a rules example per preset, and a workflow-engine example after S9 |
| Reference agents | Minimal supervisors in Python and Go (about 60 lines each) against `/api/agent/v1`, kept in `examples/` and run by an acceptance test |
| Dogfood (`yk`, `examples/dogfood`) | Move `supervisor/*.json` and `yk-ticket.ts` to the new shape in the same PR as S7 |
| Docs | `docs/interfaces/mcp.mdx`, `cli.mdx`, `web-ui.mdx`, `docs/api/*.mdx`, `docs/running/supervisors.mdx`, a new `docs/running/supervisor-contract.mdx` and `docs/running/writing-a-supervisor.mdx`, `docs/operations/security.mdx`, the glossary and a migration note |
| Skills | Update `yukta-workflows`, add `yukta-supervisor` |

### Security of the clients

A supervisor credential is a secret: it is shown once, never written to a log, and masked as `***` in every read (`SupervisorSettingsMask` already does this for settings). The CLI prints it to stdout only. The MCP `create_credential` tool is `ADMIN` only, and its description warns that the secret passes through the model's context; the UI and CLI are the recommended ways to create one. The supervisor MCP endpoint has the same per-credential limits as the REST agent API (requests per second, open polls, request size).

## Phases

Sizes: S under 2 days, M under a week, L more. Each phase is its own issue and PR.

**S0. Contract and vocabulary (docs, S).** The supervisor-contract page: supervisor and engine layers, assignments and the one-owner rule, delegation, signals and directives (schema v1), the read and act tables, the rule document outline, the compatibility promise. An ADR for the split. Reviewing it settles the open questions before code depends on them. Also the three interaction patterns, the cause of a directive, selectors, the hold ledger, preconditions and the snapshot. Also the UI vocabulary table, the refusal texts and the client compatibility table, so every surface uses the same words.

**S1. One choke point and the inventory (core, web, mcp, architecture, M).** The complete list of control and start callers, classified `OPERATOR`, `SYSTEM` or `ENGINE`. `StartWorkflowCommand` and its `CommandTarget` for a run that does not exist yet. Route `WorkflowStarter` through the dispatcher. `SupervisedControl` as the only entry, stamping the actor kind and a `channel` in every audit line. The ArchUnit rule. A test per entry that proves it reaches the choke point. Measure the extra hop on starts against `design/plans/performance.md`.

**S2. Ownership (core, web, plus, L).** Assignments and units; the exclusivity check at save of a supervisor, an assignment and a workflow definition; `groups` in the workflow definition and `group:<name>`; the run-level assignment; the default supervisor and fallback; the operator-gateway rule (`operatorAccess`, `409 CONTROLLED_BY_SUPERVISOR`); owner-only review replaces the fan-out; remove the arbiter; the migration and the `conflict` status. Tests: every overlap shape (workflow vs node, session vs workflow, prefix vs id, a new workflow version that creates an overlap, groups), no owner, default disabled, owner review of an operator command. **Client minimum, shipped in the same PR:** the new refusals (`CONTROLLED_BY_SUPERVISOR`, `AWAITING_APPROVAL`) and `via` in REST, MCP and CLI results, `GET /api/supervision`, and S8a, so that nothing a user could click is refused without a reason.

**S3. Hierarchy and delegation (plugin-api, core, plus, M).** `parents` and `delegation` modes (`ORDERED`, `ALL`, `ANY`); cycle, depth and existence checks; deleting a parent is a conflict; tier `supervisor:parent` in the input routes; `APPROVAL`, `INPUT` and `CONSULT`; the person tier as the last step; the rank rule. Tests: three levels; each mode with a silent parent, a refusing parent and a timeout; a refusal is final; a sibling cannot answer; `HIGH` reaches a person.

**S4. Engine interface and context (plugin-api, core, L).** `SupervisorEngineFactory`, `SupervisorEngine`, signals, directives, `EngineContext` (read API, ticks, state, logger, `close()`); the shell enforcement of every directive; the `SupervisorQueries` implementation limited to owned units, redacted and bounded; the engine catalog (`/api/supervisor-engines`) replacing `/api/supervisor-types`. Tests: directive outside the owned units, outside `allowedCommands`, over the rate; a failing engine; `close()` on detach; read limited and redacted. Also the `Started` and `Tick` signals, the snapshot and its cursor, selector expansion, the hold ledger in `state()`, `ifStatus` preconditions, the `initiative` allow-list with its rate limit, and the descriptor's `drivers`.

**S5. `rules` engine (plugins, core, ui, L).** Port `rule-supervisor` to the engine; the documented fact vocabulary; `read.*` in rules; `every`; the presets; run-time CRUD with versions, atomic swap and rollback; replay. UI rule form later. Tests: a rule that uses `read`; an atomic swap under load; replay equals live decisions on the same events. Also timed rules (`every`) with `read.*`, rules on reviews and delegations, and the initiative checks.

**S6. `http` engine (web, core, plugins, cli, L).** The credential, `/api/agent/v1`, the stream, directives, review and delegation round trips, per-credential limits, OpenAPI, CLI. Tests: a leaked credential cannot reach another supervisor's units or exceed its commands; revocation cuts a stream at once; a human key is refused; a slow reader loses events with `EventsMissed` and the runs are unaffected. Also `GET /snapshot`, `GET /events?after=`, and `cause`, `reason`, selectors and `ifStatus` on `/directives`.

**S7. `callout` engine and retiring the old types (plugins, core, docs, M).** Port `webhook`; delete the old types, the composite and the arbiter code; migrate the examples, acceptance tests, docs, CLI and MCP. The `workflow` supervisor type is **not** retired here: its implementation keeps running migrated workflow supervisors until S9 makes it the `workflow` engine, so no supervisor loses its decision maker in between. (The `callout` engine was later removed in #2153: an `http` supervisor with a `url` posts the same signals.)

**S8. UI (ui, web, L, in four PRs).** The design is in [UI and UX design](#ui-and-ux-design). Artboards first (updated `Supervisors.html`, `Control.html`, `RunView.html`, `SessionDetail.html`, `SessionsPhone.html`, new `Ownership.html`, `SupervisorWizard.html`, `Rules.html`; light, dark and 390 px), reviewed before code.
- **S8a, Supervisors tab and gated controls:** the page shell, the Supervisors tab (hierarchy, by workflow, Owns, Allowed operations, Hierarchy), `RequireControl`, the workflow Supervision tab, the conflicts banner.
- **S8b, decision makers and editing:** the Decision makers catalog, Settings, History, the Edit form, the groups editor.
- **S8c, rules and credentials:** Rules settings with replay and JSON; External system settings and the credential panel.
- **S8d, activity and polish:** Activity, the approval chain in the inbox, palette entries, the Control tile counts, states, Playwright. See "UI and UX design" for the screens (revised 2026-11-09).

**S9. `workflow` engine and nodes (plugins, core, L).** Begin with a spike that answers the evaluation question; then `SUPERVISOR_SIGNAL`, `SUPERVISOR_DECISION`, `SUPERVISOR_READ` and the engine, and port the example `examples/17-supervisor-workflow`. The engine declares the signal kinds that start it; `Tick` is how it initiates.

**S10. Hardening and more engines (M and L).** A threat-model review of the new endpoints against the table above; fuzzing of the directive body; a chaos test (the engine disconnects mid-review, the credential is revoked during a command); the `process` engine; a scaffold `yk new engine <name>`; the docs and the migration note (`docs/operations/security.mdx`, `docs/running/supervisors.mdx`, `docs/api`, `core/.claude/rules/supervisors.md`, `docs/contributing/core-reference.mdx` then `core/scripts/check-core-reference.sh`); regenerate `ui/openapi.json`, `docs/api/openapi.json` and `ui/src/types/api.generated.ts`.

**S11. Clients (mcp, cli, web, docs, examples, L; delivered with the phase that changes each surface).** The [Clients](#clients-mcp-cli-rest-and-others) section: the operator results and descriptions (S2); the management tools, the CLI management commands and `/api/supervisor-engines` (S4 and S7); the rules and credential endpoints, tools and commands (S5 and S6); the supervisor MCP endpoint `/api/agent/v1/mcp` and the `yukta-supervisor` skill (S6); the reference agent command and the Python and Go reference agents; the CPU guard and other examples; the Bruno collections; the OpenAPI snapshots and generated types; the docs listed there. This phase tracks the checklist; each item is done in the PR of the phase that needs it.

## Dependency order

`S0` → `S1` → `S2` (with S8a and the client minimum) → `S3` and `S4` (parallel; S8b alongside) → `S5` and `S6` (parallel, after `S4`; S8c and the matching S11 items alongside) → `S7` (after `S5` and `S6`); `S9` after `S6`; `S8d` and `S10` last. S11 is a checklist across the phases, not a block at the end.

The smallest slice that delivers D1 and D2 is **S0, S1 and S2 with S8a and the client minimum**: the contract page, the choke point, ownership with the default supervisor, and the UI and client changes that keep every refusal explained. After it nothing is open to a bare key, and behaviour is unchanged for anyone who assigns nothing.

## Risks

- **Operator lock-out.** An owner whose engine is lost, with `operatorAccess` off, leaves its workflow without a way to act. An administrator can detach it unless it is `locked`; the UI says plainly who owns each unit. Consider an `emergency` stop for the `ADMIN` role that bypasses nothing but is audited (decided in S0: not in v1; revisit in S2 if lock-out is seen).
- **The default supervisor makes D1 a no-op for unowned workflows.** That is the decision. The plan makes it visible (the Supervision tab) and switchable (`yukta.supervisor.default.enabled`).
- **Overlap checks on workflow save** add a way for a definition save to fail because of supervisors. The error names the supervisor and the nodes.
- **Legacy conflicts** after migration need a person to resolve; the `conflict` status is shown on the supervisor and in the Supervision tab.
- **Starts through the dispatcher** add a hop; measure it in S1.
- **Internal starts** (sub-workflow runs, supervisor workflows, recovery) must inherit the parent run's authority and not become a bypass. Decided in S1 with a test for each.
- **Engine trust.** In-JVM engines are trusted code, deployed by an administrator; `http`, `callout` and `process` engines are untrusted and reach the server only through directives. The documentation must state the difference.
- **The `workflow` engine** can call anything an administrator lets a workflow call. See S9.

## Questions Q1 to Q10

Decided 2026-11-08: every recommendation below was accepted. Q10 stays "decide after the S9 spike".

| # | Question | Recommendation |
|---|---|---|
| Q1 | Default `operatorAccess` of a new supervisor | `NONE`: giving a node an owner takes direct control away from keys unless the administrator grants it. |
| Q2 | Is a `session` assignment allowed? | Yes, as shorthand, with the same exclusivity. It is convenient and the check is the same. |
| Q3 | Node outputs through the read API | Off by default, opt in per supervisor, with redaction. |
| Q4 | Is `notify` in contract v1? | Yes if S0 review agrees; otherwise v1.1. |
| Q5 | `locked` assignments | Yes (S2): without it an administrator's key can always remove the supervisor that protects a workflow. |
| Q6 | SpEL in rules | Keep as an advanced `if` over the documented facts, in a read-only sandbox; the plain vocabulary covers the rest. |
| Q7 | One engine per supervisor | Yes. Composition is by parents, never by stacking engines. |
| Q8 | Legacy overlap policy at migration | Keep the oldest enabled per unit, disable the others and show `conflict`. |
| Q9 | An `ADMIN` emergency stop | Consider it in S2. It must be audited and cannot be a general bypass. |
| Q10 | Is the `workflow` engine worth building? | Decide after the S9 spike. |

## Issues

Not filed. Proposed epic: "Supervisor framework". One issue per phase, labelled `supervisors`, each citing its section here (`§S0` to `§S10`); the section wins over the issue text where they differ. Suggested first batch: S0, S1, S2.

| § | Proposed title | Size |
|---|---|---|
| S0 | Write the supervisor contract v1 and the supervisor/engine split | S |
| S1 | One choke point for control commands and starts, with the entry inventory | M |
| S2 | Ownership: assignments, one owner per unit, groups, run-level owner, default supervisor | L |
| S3 | Parents and delegation modes | M |
| S4 | Engine interface, context and shell enforcement | L |
| S5 | `rules` engine: vocabulary, presets, run-time editing, replay | L |
| S6 | `http` engine: agent endpoint and credentials | L |
| S7 | `callout` engine; retire the old supervisor types | M |
| S8 | UI: Supervisors tab, gated controls, decision makers, rules and credentials, activity (four PRs, S8a to S8d) | L |
| S9 | `workflow` engine and the supervisor nodes (spike first) | L |
| S10 | Hardening, more engines, scaffold, docs and migration | M |
| S11 | Clients: MCP (operator, management and supervisor endpoint), CLI, REST and OpenAPI, examples, skills, docs (checklist across phases) | L |
