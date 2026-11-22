# ADR 0009: Supervisor catalog

- **Status:** accepted on 2026-11-06; the model and vocabulary are in code, and the engine/ownership parts were reworked by [ADR 0002](0002-supervisor-engine-split.md) (one owner per node, engines behind supervisors, the webhook type became the `http` engine). Where the two differ, ADR 0002 wins. The Supervisors UI (S9 to S12) followed the artboards approved under epic #2144.
- **Date:** 2026-11-06 (condensed from the supervisor-catalog plan on 2026-11-22)
- **Decided by:** Arun, 2026-11-06.
- **Epic:** #1996 (sub-issues #1997 to #2008). Operator guide: [`docs/concepts/supervisors.mdx`](../../docs/concepts/supervisors.mdx); contributor guide: [`docs/contributing/supervisors.mdx`](../../docs/contributing/supervisors.mdx).

## Context

Supervisors worked end to end in the engine but were hard to use as a product: `GET /api/supervisors/types` returned bare strings, `SupervisorFactory` had no description or config schema (so the UI made people write raw JSON), every supervisor was a rules file, a model, a composite or a workflow (no generic configurable ones), they were attached after the fact and could not be set at creation, and they lived inside the Control page.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | Shape | Supervisors become a **catalog like plugins**: a type has a descriptor, a category and a config schema; a configured supervisor is an instance of a type. |
| D2 | Vocabulary | *Profile* becomes **supervisor**, *binding* becomes **attachment** (breaking, accepted in alpha). A bean or property supervisor is a read-only **system supervisor**. |
| D3 | Type descriptor | `SupervisorFactory` gains `describe()` and `getConfigSchema()`, reusing `ConfigField` and `UiDesign` so the UI's `ConfigForm` works unchanged. Categories: `GUARD`, `GATE`, `RULES`, `MODEL`, `INTEGRATION`, `COMPOSITION`, `WORKFLOW`. |
| D4 | Common settings | One shared schema for `allowedCommands`, `canAnswer`, `maxRisk`, `observeInternal`. Settings are validated against the type schema before the factory sees them; field errors come back as `settings.<key>`. `SECRET` fields are masked as `***` on every read. |
| D5 | Stored data | Schemas cover every key the factories read today. A stored supervisor that fails the new validation is logged and skipped on restore; start-up never fails. |
| D6 | Attachment source | `MANUAL` (API/UI), `DEFINITION` (declared in a session or workflow, owned by a reconciler, never edited by hand) or `SYSTEM`. The attachment cap is `yukta.supervisor.max-attachments` (default 200). |
| D7 | Declared in definitions | Session and workflow definitions carry `supervisors: [{supervisor, nodes?, enabled?}]` (max 20). A reconciler (`DefinitionSupervisors`) attaches and detaches on save, rollback, delete and start-up, with deterministic content-based ids (`def-` + 16 hex of a SHA-256) so reordering changes nothing. A failed attach never fails the save. Deleting a supervisor that a definition references is a 409. |
| D8 | API | Own REST and MCP API: `/api/supervisor-types`, `/api/supervisors`, attachments, validate, status, decisions. Old `/profiles`, `/bindings` and `/types` removed. MCP list results use wrapper records (Spring AI 2.0 limits). |
| D9 | Generic types | Four generic configurable types: `failure-guard`, `stall-watchdog`, `approval-gate` and a webhook. The first three compile into the rule engine or a clocked supervisor; fail-safe on `EventsMissed`. The webhook reuses the HTTP egress policy (allow-list, private addresses refused, no redirects), sends nothing unless `allowedCommands` names it, and never logs secrets (it became the `http` engine in ADR 0002). |
| D10 | UI | A Supervisors area modelled on Plugins: catalog, supervisors, attachments, decisions, a create wizard, a creation-time picker, an "effective supervision" card on session and workflow pages. The Control page keeps only a summary tile. UI work waits for reviewed artboards. |

## Consequences

- Breaking changes to API, model and storage (Flyway rename to `supervisor` and `supervisor_attachment`), accepted in alpha. Callers on the dev server (dogfood, examples, Bruno, UI) changed in the same PR.
- The `failure-guard`, `stall-watchdog` and `approval-gate` types survive as rule presets under ADR 0002 D8.
