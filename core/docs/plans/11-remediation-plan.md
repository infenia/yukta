# 11 Remediation plan: bugs, security and enhancements

Source: re-verification of `09-backlog.md` against `main@e8d62722` plus a fresh sweep of core and the
web/mcp edges (2026-10-13). **The sweep was static reading only; nothing below was reproduced or run.**
`Conf` says how sure the finding is: `read` = the cited lines were read and show the behaviour,
`plausible` = follows from documented framework behaviour, write the failing test first, `inferred` =
deduced from code paths, unconfirmed.

New IDs continue the backlog numbering (`S` security, `B` bug). The sweep's own labels (N1-N14) are
kept in brackets. IDs `S5-S13` and `B28-B33` were checked against `core/docs` and the module guides:
no collisions (checked on `main` only, not on unmerged branches).

Effort: **S** up to half a day, **M** 1-3 days, **L** a week or more. Sev: C/H/M/L.

## Decisions needed from you (they gate effort more than code size does)

1. **S4 deny-by-default for `PROCESS_EXECUTOR`**: breaking for anyone relying on the empty list. Ship in
   which release, and with a migration switch?
2. **S6 refuse to start on a non-loopback bind with no API key**: breaking for current Docker users, who
   bind to all addresses. Refuse, or only log a loud warning?
3. **B4 / B25 plugin-api changes** (new sealed command types, an actor field on status events): public
   API change. Is a break acceptable now, or do they wait for the next major?
4. **B21 retention policy** for session state: evict (and serve history from the log) or keep everything?
5. **S3**: fix the unused file plugin-log package, or delete it?

## P0: security exposure, small fixes

Suggested as one "edge hardening" PR (S5-S8) plus one core PR (S9, B28, R1, B31, B32).

| ID | Sev | Eff | Conf | Evidence | Issue | Fix | Test to add | Brk |
|---|---|---|---|---|---|---|---|---|
| S5 [N1] | H | S | plausible | `web/.../filter/ApiKeyFilter.java:133` (`getPath().value().startsWith("/api/")`); `mcp/.../McpEndpointFilter.java:47-49` (`equals` on the raw path) | The key check looks at the raw request path, while routing uses the decoded path with path parameters removed. A path that differs only in encoding or parameters may skip the filter and still reach the controller. | Decide on the decoded, parameter-free path elements, or reject a path containing `;` or `%` | WebTestClient: with a key set, send an encoded or parameterised form of an API path without a key and expect 401. Write it before the fix; if it passes today, downgrade the finding | no |
| S6 [N2] | H | S | read | `McpEndpointFilter.java:41` (`yukta.mcp.api-key`, default empty; blank means open, `:62-64`); `boot/.../application.yaml:5-7` (default bind 127.0.0.1) and `Dockerfile:54` (`YUKTA_SERVER_ADDRESS=0.0.0.0`) were read; the yaml comment tells operators to set only `yukta.api-key` | `/mcp` has its own key. An operator who sets only `yukta.api-key` leaves `/mcp` open, with start, control and signal tools. Combined with S4 that is command execution. Only reachable beyond loopback (`web/CLAUDE.md`: loopback unless `YUKTA_SERVER_ADDRESS`; the Docker image sets it). | `/mcp` falls back to `yukta.api-key`; see decision 2 | `@SpringBootTest`: `yukta.api-key` set and no MCP key, `/mcp` without a key is 401 | mild |
| S7 [N3] | H | S | plausible | No Host/Origin filter in `web`; query-parameter POSTs in `WorkflowController.java:97`, `ExecutionControlController.java:304,358` | A web page in the operator's browser can send simple cross-origin POSTs to the loopback server (stop, signal, limits); DNS rebinding can also read the event stream. | WebFilter with a Host allow-list; reject a non-GET whose `Origin` is present and not the server's | A POST with a foreign `Origin` and one with a foreign `Host` are rejected; same-origin UI calls still pass (`ui` e2e) | no |
| S8 [N10] | M | S | read (config) | `ApiKeyFilter.java:133` covers `/api/` only; `application-dev.yaml:8` exposes `health,info,metrics,loggers,mappings,threaddump` (read); that dev is the default profile is from the sweep | Actuator endpoints bypass the key even when one is set. | Key-check `/actuator/**` except `health`, or a separate management port | With a key set, `/actuator/loggers` without it is 401 | no |
| S9 [N5] (done) | M | S | read | `ExecutionResumer.java:119` (`"Workflow no longer exists: " + started`) | `started` is the log event record whose `toString` includes the start payload; the 404 text reaches the client. | Use session and workflow ids only | Resume of a run whose workflow was deleted: the message holds no payload value | no |
| S4 | H | S | read | `plugins/processors/process-executor/.../ProcessExecutorGateway.java:86,92-93` | Empty `yukta.process.allowed-commands` allows any executable. | Deny when empty plus an explicit enable switch (decision 1) | Empty list refuses; listed command runs; shell refused | **yes** |
| B28 [N4] | M | S | read | `core/.../service/log/InMemoryExecutionLogStore.java:23-27` (`CopyOnWriteArrayList`, `replaceAll` only at startup) | The default log store never trims: every start payload and event stays, memory grows and each append copies the list. | Bounded structure, trim to `retained-runs` as `JsonlExecutionLogStore` compaction does, never dropping a run without an end | Append more than `retained-runs` runs and check the size is bounded and an unfinished run survives | no |
| R1 | M | S-M | read (agent) | `RestartCommandProcessor.java:127,139-146`; `RestartFromNodeCommandProcessor.java:92,140`; `WorkflowOrchestrator.java:331` (`rerun` passes no admission); compare `WorkflowStarter.java:176-186` | Restart checks the limit, then acquires later, so two restarts can exceed a limit by one (the race #340 fixed for starts). | `scheduler.acquire` first, pass the admission to `execute`/`rerun`, `giveBack` on early failure | Two concurrent restarts with one free slot: exactly one proceeds, the other gets 429 | no |
| B31 [N12] | L | S | read (agent) | `core/.../control/gateway/StartIdempotency.java` (Caffeine with `expireAfterWrite` only) | Unique keys per request keep one cached entry each for the whole TTL. | Add a `maximumSize` | Insert beyond the cap, size stays bounded | no |
| B32 [N13] (done) | M | S | read (agent) | `WorkflowOrchestrator.java:235` (`Map.of()` into `rerun`), `:300` (archived and logged) | A restart-from-node records an empty payload, so a later resume or restart of that run re-fires triggers with `{}` and loses `yukta.priority`. | Pass the archived payload | Restart from a node, then resume it: the trigger payload equals the original | no |

## P1: control-plane integrity and correctness

| ID | Sev | Eff | Conf | Evidence | Issue | Fix | Test to add | Brk | Depends |
|---|---|---|---|---|---|---|---|---|---|
| S10 [N6] + B4 | M | M | read (agent) | `DefaultControlBusGateway.java:207-215,576-608`; `ControlBusService.java:395-397`; `ControlBusController.java:77,90-91`; `WaitForSignalProcessor.java:119-137`; review and audit only in `DirectiveDispatcher.java:132-148` | The raw node command is keyed by workflow and node, is neither reviewed nor audited, and can release any approval wait. Limits and priority changes are logged but not reviewed. | New sealed `SetLimits`/`SetPriority` commands through `DirectiveDispatcher`; signals only through `SignalNodeCommand`, or remove the raw endpoint | A reviewer that refuses everything also blocks limits, priority and a raw signal; each leaves a `CONTROL_REJECTED` | **yes** | decision 3 |
| B29 [N9] | M | S | read (agent) | `core/.../control/CommandSequencer.java:31-41`; `DirectiveDispatcher.java:132-148` | When a waiting command's caller cancels, its slot is released at once, so a later command can run next to the one still in progress; the cancel is not audited. | Chain the release behind `previous`; record the cancel as an outcome | Three queued commands, cancel the second: third still waits for the first | no | |
| B27 | M | S (guard), M (split) | read (agent) | `DefaultTaskTrackerService.java:843-862` (1066 lines), `WorkflowState.java:176-178` | No forward-only guard on run status; a second terminal event finalizes again and appends `finished` twice. | Return early when already terminal; extract history and cleanup projection later | Two terminal events: one `finished` log entry | no | |
| B33 [F6] | M | S-M | inferred | `WorkflowOrchestrator.java:475-485` (`doFinally` unregisters, no final status) | A cancelled sub-workflow child may never get a final status. | Test first; then finish the run as cancelled | Cancel the parent node mid-child: the child ends with a terminal status | no | |
| B26 | L | S | read (agent) | `WorkflowPreparator.java:81-91,136-155`; `WorkflowValidator.java:310,396` vs `TerminalNodeAssemblerStrategy.java:69`; `StreamTopologyDecorator.java:303,306-318` | Edges duplicated when prepare is subscribed twice; two different terminal checks; a failed checkpoint save fails the node but a failed end marker does not. | `Mono.defer`; one terminal check; one error policy | Subscribe twice and compare edge counts; a plugin that is a terminal by category but not by type is rejected consistently | no | |
| S3 | M | S | read (agent) | `logging/impl/file/FileSystemPluginLogReader.java:85`, `FileSystemPluginLogWriter.java:107-110`; no bean references them | The file log reader joins an unvalidated id into a path; the writer can overwrite on a first-write race. Dead code today. | Validate the id and always append, or delete the package (decision 5) | A traversal-shaped id is refused | no | decision 5 |

## P2: resource limits and design-level work

| ID | Sev | Eff | Conf | Evidence | Issue | Fix | Brk |
|---|---|---|---|---|---|---|---|
| S11 [N7] | M | M | read (agent) | `plugin-api/.../util/SpelUtils.java` (`withInstanceMethods()`) | Expressions can call any public instance method, including allocation-heavy ones, on strings and payload objects; one expression can exhaust memory. | Method allow-list through a custom resolver | mild |
| S12 [N8] | M | M | read (agent) | `plugins/processors/internal/script-js/.../GraalJsScriptEngine.java:63-76`, `MapperProcessor.java:354` | A script that never ends holds its thread; node timeout cancels the subscription but not the script context. | Statement limit and a watchdog that closes the context | no |
| B16 | M | M-L | read (agent) | `StreamTopologyDecorator.java:251-263`, `NodeBroadcast.java:64-69` | A branch that stops reading stalls its siblings until the idle timeout. | Propagate a downstream cancel-with-error to the run | no |
| F6 rest | M | M | read (agent) | `SubWorkflowRunner.java:55-62`, `WorkflowOrchestrator.java:357` | A child asks for its own slot while the parent holds one; no cascade stop; `parentExecutionId` is read nowhere else. | Child borrows the parent's admission; cascade stop; index the link | no |
| B25 | L | M | read (agent) | `SupervisorHost.java:181-187`; `ExecutionEvent.java:41,65` | Status events carry no actor, so a supervisor sees the pause it caused. | Actor field on status events; filter in the host | **yes** |
| B21 | L | S / M | read (agent) | `DefaultTaskTrackerService.java:86,745-758,1051-1063` | Finished runs are capped at 100 per session, but session keys are never removed (`removeSession` has no caller). | Call `removeSession` on session delete (S); TTL or max sessions (M, decision 4) | no |
| B30 [N11] | L | M | read (agent) | `ControlBusService.compositeKey:104`, `ControlHeartbeatHandler`, `ActivePluginRegistry` | Heartbeats, statistics and plugin-level commands are keyed by workflow and node only, so two sessions or runs with equal ids collide. | Include the session or execution id | no |
| S13 [N14] | L | S | read (agent) | `core/.../util/VariableResolver.java:182,250` | `${context.X}` can read any Reactor context key. | Allow-list the keys | mild |
| B19 rest | L | S | read (agent) | `SubWorkflowRunner.java:60-62` | The sub-workflow start path archives on the caller's thread. | `subscribeOn(boundedElastic())` | no |

## P3: enhancements (statuses re-verified against code)

| ID | Eff | Still open | Prerequisite |
|---|---|---|---|
| F10 | L | Node checkpoint store in PostgreSQL; a CI job running the PostgreSQL acceptance test (it is skipped in CI today); native-image hints for R2DBC and Flyway; pool and health | none; unblocks HA and durable waits |
| F2 | M | Durable signal waits, queued early signals, UI for pending approvals | F10 for a database backend |
| F3 | M | MCP roles and actor, per-session scoping, hashed and rotated keys, audit view | S5, S6 first |
| F9 | S-M | MCP version/rollback tools, diff, UI history | none |
| F4 | M | Per-provider rate limit, budget metrics, hard stop of a run | none |
| F5 | S-M | `deniedTools`, gateway-level allow-list, prompt filters | none |
| F7 | M | `KV_STORE` durability, session-memory TTL | F10 |
| F8 | S | `tools/list` pagination, tool-role turns | none |
| F11 | L | Remote workers: design only, open decisions in `10-remote-workers.md` | F10 |
| F12 | n/a | On demand. MDC today carries only the request id | none |

## Suggested order

1. **PR-1** S5 + S6 + S7 + S8: edge hardening (one theme, web and mcp).
2. **PR-2** S9 + B28 + R1 + B31 + B32: small core fixes.
3. **PR-3** S4 with a release note (decision 1).
4. **PR-4** S10 + B4: plugin-api command types (decision 3).
5. **PR-5** B29 + B27 guard + B33 + B26 + S3.
6. P2 items one at a time, then P3 by product priority.

Run the narrowest tests per `CLAUDE.md` tiers; each PR ends with `spotlessApply check` once.
