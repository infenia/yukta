# Dogfood multi-exit workflows + supervisor restart: plan and status

Branch: `claude/happy-mccarthy-btn11m`. Hand-off notes for continuing in another environment.

## Status at hand-off
Done, committed, **not compiled or tested** (the session had only JDK 21; the build needs JDK 25; `spotlessApply` not run):
- `TerminalPlugin.completionMetadata(config)` default method (`plugin-api`).
- `TerminalNodeAssemblerStrategy`: terminal SUCCESS event now carries `reached` (true/false) and, when reached, the plugin's completion metadata (`core`).
- New `plugins/terminals/outcome-terminal` (`OUTCOME_TERMINAL`: `outcome` SUCCESS|RETRY|ESCALATE|FAILED, optional `restartFrom`, `reason`) + unit test; registered in `settings.gradle.kts` and `boot/build.gradle.kts`.

Deviation from the plan below: no new `ExecutionEvent.OutcomeReported`; the outcome rides in the node-status `metadata` (rules use `nodeId` + `metadata['reached']` / `metadata['outcome']`). Adjust the plan text accordingly.

First thing to do next: with JDK 25, run `./gradlew spotlessApply :plugins:terminals:outcome-terminal:test :core:test --tests '*TerminalNodeAssemblerStrategy*'` and fix compile/style issues. Add a test for the `reached`/completion metadata in `TerminalNodeAssemblerStrategyTest`.

## Update (continued on JDK 25)
Compiled, formatted and tested: steps 0, 1 (rule actions `Restart`/`RestartFromNode`, `from: metadata.<key>`), 2 (attempt lineage lives in `RuleSupervisor`, not the engine: it generates the new id, so it counts restarts itself) and 6 (`RestartRuleAcceptanceTest`: retry restarts, escalate does not, budget stops a loop). Step 3 (`Escalate`) is dropped: an exhausted retry is simply not restarted and the last `outcome` on the stream is the signal. Step 5 docs and DF-9 done. Step 4 done: per-stage workflows (`stage_workflows`), `rules.json`, the `run_stages` driver; `self_heal` removed. Verified against a live server with a stub `gradlew`/`claude` (retry by rule, escalate to fixer, unfixable failure, `--detach`); not run with the real Claude fixer.

## Remaining steps (original list)
1. Rule actions `RestartFromNode` / `Restart` in `EventRule.commandFor` (rule-supervisor); host generates `newExecutionId`.
2. Attempt lineage across restarts (`attempt` event field) in `RestartFromNodeCommandProcessor`, so rules can bound loops.
3. `Escalate` rule action (ERROR-severity audit/ControlOutcome) for the external Claude supervisor.
4. Per-stage workflows in `examples/11-dogfood/session.json` with `OUTCOME_TERMINAL` exits, `rules.json`, thin `yukta-check.sh` driver; remove the unrolled `self_heal` chain in `scripts/_lib.sh`.
5. Docs (`docs/running/{rule-files,supervisors}.mdx`, `docs/api/streams.mdx`) and `docs/internal/bugs.mdx` entries for gaps G1-G5.
6. Acceptance test in `boot/.../SupervisorAcceptanceTest`; final `./gradlew spotlessApply check`.

---

# Plan: Multi-exit dogfood workflows + supervisor-driven restart

## Context
`examples/11-dogfood` runs `gradle check` as one long DAG. Self-healing is *unrolled inside the DAG*
(`self_heal` in `scripts/_lib.sh` generates `S → S-auto → S-r1 → S-fix1 → S-r2 → S-fix2 → S-r3` per stage
plus a `verify` node). That makes the graph large, hard to maintain, and hides the real control flow
(repair/retry) inside the workflow instead of the control plane.

Target: each stage is a small DAG with **several explicit exits**; a terminal node says *what happened*;
the **supervisor** (control plane) watches, repairs, and restarts from the failed node (or start).

## Findings: can today's supervisor do this? — No (5 gaps)
| # | Gap | Evidence |
|---|---|---|
| G1 | Rule `then` cannot emit `Restart`/`RestartFromNode` (only Pause/Resume/Stop Workflow/Node). The engine supports them (`RestartFromNodeCommandProcessor`, `RestartCoordinator`, `DirectiveDispatcher.isRestartOfArchived` allows restart of a *finished* run), but `EventRule.commandFor` rejects them. Restart also needs a pre-generated `newExecutionId`. | `plugins/supervisors/rule-supervisor/.../EventRule.java`, `ExecutionControlCommand.RestartFromNodeCommand` |
| G2 | No way to say *which exit* a run took. `CONSOLE_TERMINAL` only logs; a failure port routed into it ends the run `COMPLETED`, so success vs "needs restart" vs "unfixable" are indistinguishable from `WorkflowStatusChanged`. | `ConsoleTerminalPlugin`, `routeByExitCode` ports |
| G3 | Untaken nodes are reported `SUCCESS` (README: "Nodes that were not reached are reported SUCCESS"), so a rule keyed on an exit terminal's `NodeStatusChanged` would false-fire. Needs verification, then a `SKIPPED/NOT_REACHED` status or an explicit outcome event. | `examples/11-dogfood/README.md` Self-healing section |
| G4 | Rules are stateless per event: no per-(execution, stage) attempt budget across *restarts* (a restart is a **new** execution id; `count/withinSeconds` is keyed per execution), so a "apply → re-check → still failing" loop cannot be bounded in rules. No lineage between old and new execution. | `RuleSupervisor.countReached` key `rule/executionId` |
| G5 | Rules cannot call an LLM/fixer or escalate; the model control plane only answers `NONE/PAUSE/STOP` (no restart option, no node target). The Claude fixer lives in `yukta-heal.sh` inside the DAG. `ControlDecision`/`Fields` expose no `outcome`, exit code is only in `metadata`. | `ModelControlPlane`, `ControlDecision`, `Fields` |

Already fine: events carry `metadata` (exit code, error, stdout/stderr tail); commands are reviewed/audited/rate-limited;
`RestartFromNode` replays checkpointed parent output; a supervisor's own commands are not fed back (no loops).

## Design
### 1. Engine / plugin-api (small, additive)
- **Outcome terminal** `OUTCOME_TERMINAL` (new, `plugins/terminals/outcome-terminal`): config
  `outcome` (`SUCCESS | RETRY | ESCALATE | FAILED`), optional `restartFrom` (node id or `START`), optional `reason`.
  Emits a new `ExecutionEvent.OutcomeReported(executionId, workflowId, nodeId, outcome, restartFrom, reason, metadata, ts)`
  (add to the sealed interface; extend `Fields.eventFields` with `outcome`, `restartFrom`). Only the exit actually
  reached emits it, which also removes the reliance on G3 (still fix G3: report unreached nodes as `SKIPPED`).
- **Rule actions**: add `RestartFromNode` (`from`: literal, `event.restartFrom`, or `event.nodeId`) and `Restart` to `EventRule.commandFor`;
  the host generates `newExecutionId` (UUID) when the rule fires. Validate at load like today.
- **Attempt budget across restarts** (G4): carry `restartOf`/`attempt` in the restarted execution's payload/metadata
  (set by `RestartFromNodeCommandProcessor`), expose as event field `attempt`; rule `if: "attempt < 2"` bounds loops. When exhausted, a second rule emits escalation.
- **Escalation**: rule action `Escalate` (publishes a `ControlOutcome`/audit entry with severity ERROR and a `escalation` tag on the SSE stream) so an external LLM supervisor (Claude via `yukta controlbus watch`) acts. Keep it data-only; no LLM in-server.
- Model control plane: add a `RESTART_FROM_FAILED` option mapping (optional, follow-up; not needed for the example).

### 2. Dogfood example: many small workflows, one per stage
Replace `gradle-check` + unrolled chain with per-stage workflows (generated from the same stage table, `-m` scoping kept):

```
spotless-check:  trigger → spotless ─success→ OUT[SUCCESS]
                                     └failure→ spotless-apply ─success→ OUT[RETRY restartFrom=spotless]
                                                              └failure→ OUT[ESCALATE reason=apply-failed]
```
Exits: (1) **SUCCESS** → supervisor starts next stage; (2) **RETRY** (apply fixed it) → supervisor `RestartFromNode(spotless)` (or START);
(3) **ESCALATE** (apply failed too) → supervisor hands failure (`metadata.error`, failing node) to the fixer (Claude, `yukta-heal.sh` claude mode, reused),
then `Restart`s the stage. Same shape for compile/tests/coverage/style/spotbugs/cli (their "auto" step is empty → fail goes straight to ESCALATE); licenses/security stay fail-fast (`OUT[FAILED]`).

A tiny **pipeline rule file** (`examples/11-dogfood/rules.json`) chains stages on `OutcomeReported SUCCESS` (start next workflow),
restarts on `RETRY` with `attempt < 2`, escalates otherwise; final `verify` is just the last stage. Enabled via `yukta.supervisor.rules.file`.
`yukta-check.sh` becomes a thin driver: start stage 1, follow the control bus, act only on `ESCALATE` (invoke fixer, send restart via `yukta workflow node retry`), print the single `RESULT` line (keep its format incl. `healed=`).

### 3. Files to change
- `plugin-api/.../control/ExecutionEvent.java` (+ `OutcomeReported`), tests.
- `plugins/supervisors/rule-supervisor/.../{EventRule,Fields,When,Then}.java` + `RuleSupervisorTest`.
- `core/.../service/supervisor/SupervisorHost.java` (new-id generation for restart commands; audit field extraction already handles `RestartFromNodeCommand`), `RestartFromNodeCommandProcessor` (attempt lineage).
- `core/` orchestrator: report unreached nodes `SKIPPED` (G3) — verify first; if invasive, defer and rely on `OUTCOME_TERMINAL`.
- New `plugins/terminals/outcome-terminal` (copy structure of `notify-terminal`; register in boot).
- `examples/11-dogfood/{session.json,heal.json,rules.json,scripts/_lib.sh,yukta-check.sh,yukta-heal.sh,test-self-heal.sh,README.md}`.
- Docs: `docs/running/{rule-files,supervisors}.mdx`, `docs/api/streams.mdx` (new event), `docs/internal/bugs.mdx` (DF entries for G1–G5).
- Remove `self_heal` unrolling after the new path passes `test-self-heal.sh` equivalent.

### 4. Order of work
1. G1+G2 (event + terminal + restart rule action) with unit tests → 2. attempt lineage (G4) → 3. rule file + per-stage session + driver script → 4. G3 verification/fix → 5. docs + bugs.mdx → 6. cleanup of unrolled chain.

## Verification
- Unit: `./gradlew :plugin-api:test :plugins:supervisors:rule-supervisor:test :core:test --tests '*Restart*' --tests '*SupervisorHost*'` and the new terminal's tests.
- Acceptance: extend `boot/.../SupervisorAcceptanceTest` — stage fails, apply succeeds → rule restarts from node → second execution `COMPLETED` with SUCCESS outcome; apply fails → ESCALATE emitted, no restart; attempt budget stops a third restart.
- End to end (the three scenarios you listed): on a scratch checkout (1) clean tree → SUCCESS path; (2) introduce a formatting error → apply runs, supervisor restarts from `spotless`, SUCCESS; (3) make `spotlessApply` fail (e.g. syntax error) → ESCALATE, fixer repairs, restart, SUCCESS. Use `yukta-check.sh --watch -m core` and confirm the single `RESULT` line.
- Finally `./gradlew spotlessApply check` (Tier 4) once.

## Risks / open choices
- New sealed `ExecutionEvent` variant touches every `switch` over events (compile will find them).
- Restart of a *running* parent stops it; with outcome terminals the parent is finished, so the safer path is used.
- Sessions are in-memory: restart needs the archived execution (`ExecutionArchive` retention) — cap attempt windows accordingly.
