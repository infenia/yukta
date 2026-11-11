# ADR 0002: Supervisors own, engines decide

- **Status:** accepted on 2026-11-08.
- **Date:** 2026-11-08
- **Decided by:** Arun, 2026-11-08 (D1 to D14 and Q1 to Q10; Q10 waits for the S9 spike).
- **Plan:** [`design/plans/supervisor-framework.md`](../plans/supervisor-framework.md). Epic #2144, this phase #2145.
- **Contract:** [`docs/reference/supervisors/contract.mdx`](../../docs/reference/supervisors/contract.mdx), version 1.

## Context

Today a supervisor type is the decision logic and its configuration at once. Several supervisors can match one node and any one may veto a command, so an arbiter and flap windows exist to keep them apart. REST, MCP and CLI reach the dispatcher with no supervisor in the way, and starts skip review. A program that is not a plugin has no identity: a key-based agent counts as a person for the risk rule. Nothing says what an outside decision maker may read or do.

## Decisions

| # | Decision |
|---|---|
| D1 | Nobody has direct control. Everything goes through supervisors, people included. A lenient default supervisor exposing today's endpoints carries commands for anything no supervisor owns. |
| D2 | Starts need a supervisor like any command. |
| D3 | The hierarchy is explicit parent links; several parents are allowed; no scope relation. |
| D4 | The first external integration is an inbound endpoint (the `http` engine). |
| D5 | Two layers: the supervisor is configuration and enforcement, the engine is a plugin behind one interface. One engine per supervisor. |
| D6 | One owner per node. No overlap, no override. A supervisor may only delegate to its parents. |
| D7 | The delegation mode (`ORDERED`, `ALL`, `ANY`) is chosen per kind. |
| D8 | The old supervisor types are retired. `failure-guard`, `stall-watchdog` and `approval-gate` stay as rule presets. |
| D9 | Run-level commands have their own assignment: one run-level owner per workflow. |
| D10 | No independent vetoes. |
| D11 | Three interaction patterns: observe, respond, initiate. Every directive carries a cause. |
| D12 | Initiative has a required `reason`, its own rate limit (default 10 per minute), an audit flag and an `initiative` allow-list (default none). |
| D13 | Selectors (`owned.running`, `workflow:<id>`, `node:<ids>`) and the hold ledger are in contract v1. |
| D14 | The `rules` engine may initiate through timed rules, under the same limits. |

Contract details settled in S0 (recorded in the page and the plan): `notify` is in v1; the error codes are `CONTROLLED_BY_SUPERVISOR` (409), `OWNERSHIP_CONFLICT` (409), `NOT_OWNED` (409), `AWAITING_APPROVAL` (202) and `CONTROL_REJECTED` (403); the CLI exits with `6` on a refusal (4 is the server-error code); the version header is `Yukta-Supervisor-Contract`; the selector cap is 200 and the default initiative limit 10 per minute; `locked` assignments are in (S2); an admin emergency stop is not in v1.

## Consequences

- **Loss of independent vetoes.** The owner and its delegation chain are the only authority. An administrator can always detach an unlocked supervisor.
- **The default supervisor** makes D1 a no-op for unowned workflows. That is intended; the Supervision panel shows it and `yukta.supervisor.default.enabled` switches it off.
- **Breaking changes** (alpha): `/api/supervisor-types`, `attach_supervisor`, `detach_supervisor`, the CLI `supervisors types` and the `supervisors` block of definitions are replaced (S2, S7). Old overlapping attachments are not merged: the oldest stays enabled and the rest show a `conflict` status.
- **Saving a workflow definition can fail** because of supervisor overlap. The error names the supervisor and the nodes.
- **Trust.** In-JVM engines are trusted code and may depend on `plugin-api` only (an ArchUnit rule). `http`, `callout` and `process` engines are untrusted and reach the server only through directives.
- **One choke point** (`SupervisedControl`, S1) and an ArchUnit rule make a new bypass fail the build.

## Decisions made while building S2 (2026-11-08)

- **E1** No `global` target. A lenient default supervisor covers unowned work and can be switched off (`yukta.supervisor.default.enabled=false`); a legacy `Global` attachment migrates to no assignment with a WARN.
- **E2** Bean and property supervisors stop reviewing (replaced by engines in S4).
- **E3** Migrated supervisors get `operatorAccess = NONE`, like new ones.
- **E4** `group:<name>` reuses `Node.group` (display stage, else the id prefix); no new `groups` map.
- **E5** The S8a UI waits for reviewed artboards.
