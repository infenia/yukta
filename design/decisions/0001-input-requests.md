# ADR 0001: Input requests and supervisor coordination

- **Status:** accepted on 2026-11-01. D1 to D15 were put to Arun in three rounds of questions; the outcome and the new decisions D16 and D17 are below. A later change is recorded here in the PR that acts on it.
- **Date:** 2026-11-01
- **Prepared by:** Claude Code, at the request of Arun, from a read of `origin/main` at fe3c5a463 (nothing was built or run). **Decided by:** Arun, 2026-11-01. Where Arun chose an option other than the recommended one, the row says so.
- **Plan:** `design/plans/input-requests.md`. Epic #1787, this phase #1788.

## Decisions

### Decisions proposed by the plan (D1 to D8)

| # | Decision | Outcome | Decided by |
|---|---|---|---|
| D1 | Name of the concept | `InputRequest` | Arun, 2026-11-01 (recommended option) |
| D2 | `SignalWaits` and the signal API | **Not the recommendation (wrap, deprecate, remove later).** Decided: replace in one go. `SignalWaits` is deleted in phase 4 with no fallback, and the signal API (`SignalNodeCommand`, REST `/signal`, MCP `signal_node`, `/approvals`, `list_pending_approvals`, CLI `approvals`) is removed too. Order: phase 4 (breaking label), 5a new endpoints, 5b UI, 5c docs page, 5d clients, examples and docs move (#1804), 5e server removal (#1805); one release, because approvals must stay answerable until the new path exists | Arun, 2026-11-01 |
| D3 | Default risk | HIGH for `WAIT_FOR_SIGNAL` and tool approvals (today's human-only behavior), MEDIUM for `ASK_INPUT` | Arun, 2026-11-01 (recommended option) |
| D4 | Supervisor answering a HIGH risk request | Never, in the default `AnswerAuthorizer` | Arun, 2026-11-01 (recommended option) |
| D5 | Durable store | Plus (PostgreSQL); in-memory in Community | Arun, 2026-11-01 (recommended option) |
| D6 | One-time token answers without an API key | Yes, phase 6 only | Arun, 2026-11-01 (recommended option) |
| D7 | Internal session | A session flag plus ADMIN-only creation | Arun, 2026-11-01 (recommended option) |
| D8 | Workflow-backed supervisors | After the arbiter, reserved capacity and loop guards (phase 8) | Arun, 2026-11-01 (recommended option) |

### Decisions that the code reading added (D9 to D17)

| # | Decision | Outcome | Why (finding) |
|---|---|---|---|
| D9 | How a plugin reaches the gateway | `InputGateway` is a **Spring bean** that plugins take through `ObjectProvider<InputGateway>`, like `SubWorkflowGateway`. Its `ask` reads session, workflow, execution and node from the Reactor context (`Mono.deferContextual`), so identity is still bound by the engine. No per-node gateway object, no `forNode` factory | F1 |
| D10 | Typing the actor | Add `ControlActor.KIND_KEY` (`SUPERVISOR`, `KEY`, `TOKEN`) written next to the actor by every writer; the authoriser reads the kind and never infers it from a string prefix. **Validate every supervisor id (`[A-Za-z0-9._-]{1,64}`, no `:`) and migrate stored ids** (Arun chose this over "validate new, keep old"); it is its own issue, #1803, shipped before phase 3 | F3 |
| D11 | Execution log types | One new type `INPUT` (data: event name, request id, tier, responder, decision); the five `INPUT_*` names of the plan become the `event` field. Commands already log as `CONTROL_APPLIED` and `CONTROL_REJECTED` | F8 |
| D12 | Answer on a standby server | Reject with the existing `NotLeaderException` (HTTP 503), do not forward. There is no proxy in the HA design | F6 |
| D13 | What the durable store is for | Not rehydrating live waiters (a failover aborts the leader's runs). It keeps answered requests for a resumed run, history, and lets a resumed run **reopen** a request that was cancelled as `run-ended`, so a person is not asked twice | F2, F6 |
| D14 | Redaction | Add `InputRedactor` in core. `LogSanitizer` lives in `mcp` and `ConfigRedactor` is for definitions, so neither is usable from core as is; extract the pattern list into a shared core utility that `LogSanitizer` also uses | F9 |
| D15 | Store contract tests | `InputRequestStoreContract` in `plus/src/testFixtures/.../testkit`, run for both the in-memory and the Postgres store from the `plus` test source set (core cannot depend on plus test fixtures) | F10 |
| D16 | Order of the signal API removal | Legacy clients keep working through a bridge from `SignalNodeCommand` to an open input request until the server removal (#1805), which is gated on #1804 (clients, examples, docs moved). Phases 4 and 5a to 5e ship in one release | consequence of D2 |
| D17 | Supervisor ids | Same as D10, tracked as #1803 (phase 3a). Ids already stored are renamed by a Postgres migration with a mapping table; a Spring bean supervisor with an invalid `id()` fails start-up | Arun, 2026-11-01 |

## Findings (the eleven checks of #1788)

Each finding says what was read. "Confirmed" means read in code; "inferred" means concluded from code that was read but not run.

### F1. How plugins receive gateways and identity
- Plugins read `sessionId`, `workflowId`, `executionId`, `nodeId` and `payload` from the Reactor context. The engine writes them (`ExecutionContextBuilder.java` constants `CTX_SESSION_ID`, `CTX_WORKFLOW_ID`, `CTX_EXECUTION_ID`, `CTX_NODE_ID`, `CTX_PAYLOAD` at lines 39 to 51, applied in `build()`). `WaitForSignalProcessor.java:172-173` reads `executionId` and `nodeId` with `ctx.getOrDefault`. A plugin cannot choose them. Confirmed.
- Gateways are not in the context. `SubWorkflowProcessor.java:60,156` takes `ObjectProvider<SubWorkflowGateway>` and `SubWorkflowRunner` is a core bean. Only `ResultCollector` rides in the context (`SubWorkflowRunner.java:75`, read at `TerminalNodeAssemblerStrategy.java:147`). The plan's statement "put the gateway in the Reactor context like `ResultCollector`" was wrong. Confirmed.
- Consequence: D9. `plugin-api` gets `InputIdentity.from(ContextView)` with the same key strings; `InputGateway.CONTEXT_KEY` and `InputGatewayFactory.forNode` are dropped. The spec gets an informational `requester` string (plugin type), not used for authorisation.

### F2. Message ids, resume and the request key
- A resume starts a **new execution id** (`ExecutionResumer.start`, `newId = UUID.randomUUID()`) and replays only the recorded output of the boundary nodes (`plan.boundary()`); every other node runs again. Confirmed.
- A message gets a fresh random id when created (`DefaultMessage.create`, `DefaultMessage.java:229-231`). So a message id is stable across a resume only when the waiting node's input is a replayed output of a finished node. Nodes that run again produce new ids and ask again. Confirmed.
- Consequence: the request key built from session, workflow, node, message id and name (without the execution id) is right, and "a resumed run gets the stored answer" holds only for replayed input messages. In Community (in-memory request store, lost on restart) nothing is remembered; this is documented, not fixed.
- **Suspected existing defect (inferred, not run).** `WaitForSignalProcessor.remembered` looks up `answers.getAll(executionId, "signal:<node>")` with the execution id from the context, which on a resume is the **new** id, and `ExecutionResumer` copies only boundary nodes' outputs, so the pseudo node `signal:<node>` is not carried over. The unit test that covers it (`WaitForSignalProcessorTest.answerIsRememberedWhenTheSameMessageResumes`) uses the same execution id `e1` for both runs and a store whose `getAll` ignores the id, so it does not model the real resume. The documented behavior ("a resumed run reuses the answer") is therefore probably not working end to end. A failing test through `ExecutionResumer` should come first; the fix (for example putting the previous execution id in the resumed payload or copying `signal:` records in the resumer) is a separate bug issue, not part of this Epic.

### F3. Actor integrity
- `ControlActor.CONTEXT_KEY` is written in three places only: `SupervisorHost.java:360` (the supervisor id), `ApiKeyFilter.java:219-222` (`"key:" + name`, only when the authenticator has named keys) and `McpEndpointFilter.java:196-198` (same). No header, query or body sets it. Confirmed.
- Gaps: (a) a single unnamed `yukta.api-key` gives actor `null` although the caller is authenticated; (b) supervisor profile and binding ids are only checked for null (`SupervisorBinding`, `SupervisorProfile`), so a binding id such as `key:admin` makes a supervisor's actor string equal to the human key `admin`, in the audit and in any code that types an actor by prefix. Creating bindings needs ADMIN, so this is a hardening item, not an escalation. Confirmed by reading; not exploited.
- Consequence: D10, implemented in phase 3.

### F4. Consumers of the sealed types
`ExecutionEvent` (switches and checks that must handle the five new records, or compile without a default):
`RunAnalytics.java:449-451`, `ModelControlPlane.java:177-198`, `Fields.java:50-72` (rule fields), `EventSeverity.java:35-39`, `SupervisorHost.java:315` (instanceof). Publishers: `DirectiveDispatcher`, `TrackerEventApplier`. Serialization: `EventsController.java:26-27` lists the subtypes in `@RegisterReflectionForBinding` for the native image, so each new record must be added there. The SSE `type` filter takes **one** value (`EventsController.java:112`, `equalsIgnoreCase`), so the plan's `type=InputRequested,InputEscalated` needs either comma support (phase 5a) or two streams. UI: `ui/src/features/control/lib/activity.ts`, `ui/src/features/control/supervisors/ProfileDialog.tsx`; CLI: `cli/internal/client/events.go`, `cli/internal/commands/controlbus/watch.go`.
`ExecutionControlCommand`: `DirectiveDispatcher.java:267`, `ControlReviewer.java:153` (stop detection, no change), `ModelControlPlane.java:223`, `EventRule.java:171-181` (rule name to command), `Fields.java:94-123` (node id and reason switches). Command processors are not a sealed switch: each implements `ControlSignalProcessor.canProcess` and is picked by that method and `getPriority`, so new processors register by being beans. Confirmed.

### F5. Caller identity inside the dispatcher
`ApiKeyFilter` and `McpEndpointFilter` put the `ApiKeys.Match` (role, sessions) under `CallerIdentity.CONTEXT_KEY` and the actor under `ControlActor.CONTEXT_KEY` on the request chain. `DirectiveDispatcher.dispatchOrdered` reads the actor with `Mono.deferContextual` (`:135`) and runs the processor inside that same subscription, so a processor can read both. Supervisor commands carry only the actor (`SupervisorHost.java:360`), never a `Match`. Confirmed by reading; not run.

### F6. Leader and HA
`LeaderElection` (`service/leader/LeaderElection.java:11`); a single server is always leader. With `yukta.ha.mode=postgres` one server holds a lease, a standby refuses with `NotLeaderException` (HTTP 503, `yukta.executions.rejected{reason=not_leader}`), "clients send requests to the leader; there is no proxy", and after a failover the old leader's unfinished runs are marked `ABORTED` (`core/.claude/rules/workers-and-ha.md`). So waiters live on the leader's JVM; the deadline sweeper and the delivery worker must run only when `isLeader()` is true; an answer on a standby gets 503. Open requests of aborted runs are cancelled, not rehydrated (D12, D13).

### F7. Run end hook
`WorkflowOrchestrator` builds a once-only `cleanup` runnable (about lines 545 to 560: `executionControlRegistry.unregister`, `clearIfPending`, `safeStopSink`) and attaches it to `doOnTerminate` and `doOnCancel` (about lines 600-601), with the comment that cleanup must precede the terminal signal. The broker cancels the execution's open requests there (`inputBroker.cancelExecution(executionId, "run-ended")`). A cancelled node is covered by cancelling the subscriber of `ask`.

### F8. Execution log types
`ExecutionLogEvent.Type` has nine values: `RUN_STARTED`, `RUN_FINISHED`, `RUN_ABORTED`, `CONTROL_APPLIED`, `CONTROL_REJECTED`, `NODE_FINISHED`, `RUN_RESUMED`, `IDEMPOTENCY_KEY`, `LIMITS_CHANGED`. Consumers filter or switch on them: `ExecutionRecovery.java:146,158,205,242,253`, `LogRetention.java:101-102` (a `case` on type), `RunAnalytics.java:373-378`, `StartIdempotency.java:105`. Every command (including the new ones) is already logged as `CONTROL_APPLIED` or `CONTROL_REJECTED` with the actor. D11: one new `INPUT` type, with recovery and retention ignoring it, and a test per consumer.

### F9. Redaction
`ConfigRedactor` (`service/workflow`, `SECRET_NAME` pattern, replaces secrets in workflow and session definitions) and `LogSanitizer` (`mcp/util`, `DEFAULT_PATTERNS`, `SECRET_KEY`, control characters, `maxLineChars`, `clean(line)`) exist. Neither fits: `core` cannot use `mcp`, and the first works on definitions. D14.

### F10. Persistence and edition seams
- Latest Flyway migration is `V7__supervisors.sql`, so the input tables take **V8** (`plus/persistence-postgres/src/main/resources/db/migration`; V3 checkpoints, V4 leader lease, V5 kv, V6 history).
- `FeatureGateEnvironmentPostProcessor.GATES` refuses a setting when the class that provides it is absent (a row is `Gate(setting, value, providerClass, Edition, buildHint)`); the Postgres row uses `PostgresPersistenceAutoConfiguration` and the hint `-Pyukta.persistence=postgres`. A new `yukta.input.store-type=postgres` setting gets a row of the same shape.
- Defaults live in `DefaultsAutoConfiguration` as `@Bean @ConditionalOnMissingBean(X.class)` (lines 44, 65, 82, 93); the input store default is one more. No "in-memory notice" code was found by searching `config` for it (the editions plan mentions one); phase 2 locates it (start with `EditionInfo` and `boot`) or adds a log line.
- Contract tests are in `plus/src/testFixtures/java/com/infenia/yukta/plus/testkit/` (execution log, leader election, node checkpoint, session config, workflow definition). There is none for `SupervisorStore`. D15.

### F11. UI and docs seams
- The pending approvals strip is `ui/src/features/workflow/components/PendingApprovals.tsx` with `ApprovalRow.tsx` and `ui/src/features/workflow/api/approvals.ts` (`getPendingApprovals`, `sendDecision` through the signal endpoint). It is used by `WorkflowDetailsPage.tsx` and the overview (`ui/src/features/overview/OverviewPage.tsx`, `api.ts`). Tests: `PendingApprovals.test.tsx`.
- OpenAPI and types: `yk gradle :boot:test --tests "*OpenApiSnapshotTest" -Pyukta.openapi.update=true`, then `yk tool -C ui pnpm run api:types -- openapi.json` (`ui/CLAUDE.md:170`); the `ui` check fails when `ui/src/types/api.generated.ts` is stale. (`docs/api/openapi.json` is the second snapshot named in the plan; confirm the same task refreshes it.)
- Mintlify navigation is `docs/docs.json` (`building/approvals-and-signals` at line 81, `running/supervisors` at line 110).

Arun accepted D9, D11, D12, D14 and D15 as proposed (2026-11-01) and chose the recommended option for D13 (reopen the old request).

## Consequences for the sub-issues
Each affected issue has a section "Changes from phase 0" and "Changes from the decisions round" appended. New issues: #1803 (phase 3a, supervisor ids), #1804 (phase 5d, clients, examples and docs), #1805 (phase 5e, remove the legacy signal API). Phase 0 sections appended to: #1789 (gateway shape, actor kind constant), #1790 (gateway bean, redaction, log type, contract tests), #1791 (actor kind, id validation, processors by `canProcess`), #1792 (gateway through `ObjectProvider`), #1793 (SSE filter, reflection hints, regeneration commands), #1794 (real file names), #1796 and #1799 (HA behavior, reopen instead of rehydrate).

## Open points left for later phases
- Confirm F2's suspected defect with a failing test and file it as a separate bug (not part of this Epic). Note that D2 deletes `WAIT_FOR_SIGNAL`'s `SignalWaits` use in phase 4; the checkpoint-store memory in `WaitForSignalProcessor` is revisited there.
- Find or add the in-memory notice (F10) in phase 2.
- Whether `docs/api/openapi.json` is refreshed by the same task (F11).
