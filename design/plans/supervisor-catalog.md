# Supervisor catalog: supervisors as first-class, configurable, catalogued components

Design for turning supervisors into a catalogue like plugins: supervisor types with a descriptor and a config schema, configured supervisors, attachments, four generic supervisor types, supervisors declared in session and workflow definitions, and a dedicated Supervisors area in the UI with its own REST and MCP API.

Status (2026-11-06): **proposal**, nothing built. Agreed with Arun on 2026-11-06. The "today" statements were read from the code at `origin/main` b0b332ded. The work is tracked in the epic and sub-issues listed under [Issues](#issues). Each issue cites its section here (`§S1` to `§S12`), and its section wins over the issue text where they differ.

## Context

Supervisors work end to end in the engine: scopes, the arbiter, composites, workflow supervisors, approvals and input answering. As a product, though, they are hard to use:

- **No catalog.** `GET /api/supervisors/types` returns bare strings. `SupervisorFactory` has no description, category, icon or config schema, so the UI makes people write settings as raw JSON (`ProfileDialog.tsx`). Plugins, by contrast, have `describe()`, `getConfigSchema()`, a list/detail page and the schema-driven `ConfigForm`.
- **No generic supervisors.** Every supervisor today is either a rules file, a model, a composite or a workflow. There is nothing like the generic core plugins (`HTTP_REQUEST`, `MAPPER`) that you only configure.
- **Configured in the wrong place.** Supervisors are attached after the fact with "Attach supervisor" (`ScopedSupervisors.tsx` on the workflow and session pages). They cannot be set when a session or workflow is created, and session and workflow definitions do not mention them, so imports, templates, MCP-created sessions and versions carry none.
- **No home.** Supervisors are a section of the Control page (`SupervisorCockpit.tsx`), next to unrelated capacity and worker panels.

The outcome: a **Supervisors** area modelled on Plugins (catalog, configured supervisors, attachments, decisions), its own REST/MCP API, four new generic configurable supervisor types, and supervisors declared in session and workflow definitions that are attached when the definition is saved. The app is alpha, so breaking API, model and storage changes are accepted.

**User decisions (2026-11-06):**
- Supervisors are declared **in the definition** (session and workflow) and reconciled on save. Hand-made attachments stay separate.
- New generic types: **failure guard, stall watchdog, approval gate, webhook**.
- The Control page keeps a **summary tile only**.
- Vocabulary is renamed (alpha, breaking OK): *profile* becomes **supervisor** and *binding* becomes **attachment**.

## Target model and vocabulary

| Concept | Today | New name | What it is |
|---|---|---|---|
| Supervisor type | `SupervisorFactory.type()` string | **Supervisor type** | A catalog entry, like a plugin: descriptor, category, config schema, example |
| Configured instance | `SupervisorProfile` | **Supervisor** | id, type, settings, dryRun, description |
| Attachment to a scope | `SupervisorBinding` | **Attachment** | id, supervisorId, scope, enabled, **source**, origin |
| Bean/property supervisor | bean, invisible in config | **System supervisor** | shown read-only with `source=SYSTEM` |

`source` is `MANUAL` (made over the API/UI), `DEFINITION` (declared in a session or workflow; owned by the reconciler; never edited by hand) or `SYSTEM` (beans). `origin` is the `(sessionId, workflowId?)` that declared a DEFINITION attachment.

## S1. Supervisor type descriptor and config schema (plugin-api, core, plugins)

- `plugin-api/.../control/SupervisorFactory.java`: add `default SupervisorDescriptor describe()` and `default List<ConfigField> getConfigSchema()`. Reuse `ConfigField` and `UiDesign` from `plugin/core`, so the UI's `ConfigForm` works unchanged.
- New record `SupervisorDescriptor(String displayName, String description, String usagePattern, SupervisorCategory category, Optional<UiDesign> uiDesign, Map<String,Object> exampleSettings)`, plus `enum SupervisorCategory { GUARD, GATE, RULES, MODEL, INTEGRATION, COMPOSITION, WORKFLOW }`.
- Kind (`SUPERVISOR` or `CONTROL_PLANE`) is derived in core from the created class, as `SupervisorInfo` does today. It is not declared.
- Core `SupervisorCommonSettings`: the shared schema for `allowedCommands` (LIST with the command names as `options`), `canAnswer`, `maxRisk` (ENUM), `observeInternal`. It sits next to `SupervisorCapabilities` and is the one source of truth for those keys.
- `SupervisorService.saveProfile` validates the settings against the type schema (required, type, enum options, unknown keys refused) **before** `factory.validate`, and returns field errors as `settings.<key>`. Extract a shared `ConfigSchemaValidator` if plugin config validation already has one; check `PluginChecks` first.
- Implement `describe()` and the schema on all six factories: `RuleSupervisorFactory`, `RuleControlPlaneFactory`, `ModelControlPlaneFactory`, `RulesFirstControlPlaneFactory`, `CompositeSupervisorFactory`, `WorkflowSupervisorFactory`. Take the descriptions from `docs/running/supervisors.mdx`. Rules and children are LIST/MAP fields, which `ConfigForm`'s `JsonField` renders.
- Secret handling: `ConfigField.Type.SECRET` fields are masked as `***` in every read, extending the current key-name heuristic.
- **Do not break stored and example supervisors.** The schemas must cover every key the factories read today, not only the capability keys: the model plane's `events`, `min-confidence` and model settings, composite and workflow settings, and `rules`. `examples/dogfood/supervisor/profile.json`, the profiles in `examples/16` and `examples/17`, and `rules.example.json` must validate. On `restore()`, a stored supervisor that fails the new validation logs a WARN and is skipped; startup does not fail. The dev server builds from `origin/main`, so the dogfood supervisor has to keep starting.

## S2. Domain rename and attachment source (core, plus/persistence-postgres)

- Rename `SupervisorProfile` to `Supervisor` (add `description`) and `SupervisorBinding` to `SupervisorAttachment(id, supervisorId, scope, enabled, source, originSessionId, originWorkflowId)`. Rename `SupervisorStore` methods to match. Update `BoundSupervisor`, `SupervisorHost`, `SupervisorResolver`, composites and `WorkflowSupervisor.bindTo`. Keep `SupervisorIds.RULE`.
- `InMemorySupervisorStore` and `R2dbcSupervisorStore` get a new Flyway migration (next free number, V10 today): rename the tables to `supervisor` and `supervisor_attachment`, then add `source`, `origin_session_id`, `origin_workflow_id` and an index on `(origin_session_id)`. Update the store contract test in `testFixtures`.
- The cap becomes `yukta.supervisor.max-attachments` (default 200) instead of `MAX_BINDINGS = 50`; DEFINITION attachments count too. Document it in `core-reference.mdx`.
- `SupervisorService.removeForSession` removes both MANUAL and DEFINITION attachments of the session (as today).
- REST paths are unchanged in this issue: the web layer maps the old DTOs onto the new service, so the dogfood keeps working until S3.

## S3. Supervisor API v2: catalog, supervisors, attachments (web, mcp, cli, dogfood, examples), breaking

| Method and path | Purpose | Role |
|---|---|---|
| `GET /api/supervisor-types` | Catalog summaries: `type, displayName, category, kind, icon` | VIEWER |
| `GET /api/supervisor-types/{type}` | Details: descriptor, `configSchema`, `commonSchema`, `defaultAllowedCommands`, `exampleSettings` | VIEWER |
| `GET /api/supervisor-types/{type}/usage` | Supervisors of this type | VIEWER |
| `GET/POST /api/supervisors`, `GET/PUT/DELETE /api/supervisors/{id}` | Configured supervisors. The list includes SYSTEM ones (read-only), the attachment count and live status | VIEWER / ADMIN |
| `POST /api/supervisors/validate` | Validate a draft supervisor without saving; returns field errors | ADMIN |
| `GET /api/supervisors/{id}/attachments`, `GET /api/supervisors/attachments?session=&workflow=&source=` | Attachments, including the *effective* supervisors of a session or workflow (global + session + workflow + sections) | VIEWER |
| `POST /api/supervisors/attachments`, `PATCH /api/supervisors/attachments/{id}` (`enabled`), `DELETE ...` | MANUAL only. DEFINITION and SYSTEM attachments give 409 "edit the definition" | ADMIN |
| `GET /api/supervisors/status` | Runtime (old `GET /api/supervisors`) | VIEWER |
| `GET /api/supervisors/decisions?supervisor=&attachment=&limit=` | Decision feed | VIEWER |

- Remove `/api/supervisors/profiles`, `/bindings` and `/types` (breaking-change label). Split `SupervisorBindingsController` into `SupervisorTypesController` and `SupervisorsController`. DTOs go in `web/.../dto/response/` and mirror `PluginSummary` and `PluginDetails`.
- MCP (`AppMcpTools`, `provider/SupervisorProvider`, `McpToolAccess`):
  - new `list_supervisor_types` and `get_supervisor_type`, whose DTO **includes `configSchema`** (do not copy the gap in `mcp/dto/PluginDetails`);
  - new `save_supervisor` (ADMIN);
  - `list_supervisors`, `attach_supervisor` and `detach_supervisor` move to the new vocabulary. Return wrapper records for lists: a `Flux` tool result is cut to its first element and `Mono<List<T>>` with output schemas fails at startup (Spring AI 2.0).
- CLI: `cli/internal/client/admin.go` changes `GetSupervisors` to `/api/supervisors/status`, and adds `yukta supervisors types|list|show`.
- Callers that must change **in the same PR**, because the dev server is built from `origin/main`:
  - `examples/dogfood/src/ticket/yk-ticket.ts` (lines 283-284, `profile.json`/`binding.json`)
  - `examples/dogfood/supervisor/*.json`, `examples/16-supervisor-profiles`, `examples/17-supervisor-workflow`
  - `boot` `WorkflowSupervisorAcceptanceTest`, Bruno collections, the UI `features/control/api/supervisors.ts` (kept compiling)
  - #1923's dogfood supervisor workflow files, if #1923 has landed
- Regenerate `ui/openapi.json`, `docs/api/openapi.json` and `ui/src/types/api.generated.ts` with the project task. Update `docs/api/` and `docs/running/supervisors.mdx` (endpoints).

## S4. Supervisors declared in session and workflow definitions (core, web, mcp)

- Model: `record SupervisorRef(@NotBlank String supervisor, @Nullable List<String> nodes, @Nullable Boolean enabled)`.
  - `WorkflowDefinition.supervisors` (`List<SupervisorRef>`, default empty, max 20). Without `nodes`, the scope is `workflow:<s>/<w>`; with `nodes`, it is the section `workflow:<s>/<w>//<sel,...>`, using the selector rules of `SupervisorScope.Section`.
  - `SessionConfigData.supervisors` (`List<SupervisorRef>`, `nodes` not allowed). Its scope is `session:<s>`.
  - Add both to `WorkflowDefinitionRequest`, the session request DTO, `SessionSnapshot` and the export/import and template JSON.
- Validation in the session validate path (`POST /api/sessions/validate` and save): the supervisor exists and is not SYSTEM, the selectors are valid and match at least one node of the workflow, and there are no duplicates. Field errors: `workflows.<w>.supervisors[i].supervisor`.
- Reconciler `DefinitionSupervisors` (core `service/supervisor/`):
  - Runs after a successful session save (`SessionService` apply path), workflow version rollback (check that it goes through apply), session delete, and once on `ApplicationReadyEvent` / leader takeover, after `SupervisorService.restore`.
  - Computes the desired DEFINITION attachments of the session, diffs them against the stored ones with `originSessionId = s`, then attaches and detaches. It never touches MANUAL ones.
  - Ids are deterministic, valid and content-based: `def-` + the first 16 hex characters of the SHA-256 of `session/workflow/supervisor/sorted node selectors`. Use no list index, so reordering refs changes nothing and running runs stay supervised without a detach and reattach. Duplicate refs are refused, so the id is unique. Hashing is needed because session and workflow ids may contain `#` or be long. Toggling `enabled` updates the attachment in place.
  - A failed attach does not fail the save (the definition is the truth). It is logged under run id `supervisors` and surfaced in the session's effective supervision as `status=error`.
- `SupervisorService.delete(id)` of a supervisor referenced by a definition gives 409, naming the sessions and workflows.
- Versions, diff and rollback carry the field through `WorkflowDefinition`. `diff_workflow_versions` shows it.
- MCP `get_session_creation_instructions` and the create-session guide document the `supervisors` field.

## S5. Failure guard and approval gate types (plugins)

New module `plugins/supervisors/guards` (in `settings.gradle.kts`, added to `boot` as required). Both types compile their settings into the existing rule engine (`RuleSet`, `EventRule` with `count`/`withinSeconds`, `ApprovalRule`), so no new engine is needed. Depend on `rule-supervisor`, or move the rule engine into a shared library module if the dependency is awkward.

- `failure-guard` (category GUARD):
  - `failures` (NUMBER, default 3), `withinSeconds` (60), `nodes` (LIST of selectors, optional), `errorContains` (STRING, optional)
  - `action` (ENUM `PAUSE_WORKFLOW`, `STOP_WORKFLOW`, `PAUSE_NODE`; default `PAUSE_WORKFLOW`)
  - `defaultAllowedCommands` = exactly the action's command.
- `approval-gate` (category GATE):
  - `commands` (LIST with options `StopWorkflowCommand`, `RestartCommand`, `RestartFromNodeCommand`, `SkipNodeCommand`, `StopNodeCommand`, `SetLimitsCommand`), `nodes` (optional selectors)
  - `risk` (ENUM, default HIGH), `route` (STRING, optional), `ttl` (seconds), `reason` (STRING)
  - It only reviews and emits no commands.
- Both have descriptors, examples and unit tests with `StepVerifier`, plus a `boot` acceptance test that attaches each through the API.

## S6. Stall watchdog type (plugins)

`stall-watchdog` (category GUARD) in `plugins/supervisors/guards`. It is a real `ExecutionSupervisor`, because it needs a clock.

- State: running nodes from `NodeStatusChanged` and the last log time from `LogLine`, in a bounded map (at most 10,000 nodes, the oldest evicted). Timing comes from a `Flux.interval` tick (`checkEverySeconds`, default 10).
- Settings:
  - `maxRunningSeconds` and `maxSilenceSeconds` (at least one required)
  - `nodes` (selectors)
  - `action` (ENUM `PAUSE_NODE`, `STOP_NODE`, `PAUSE_WORKFLOW`, `STOP_WORKFLOW`; default `PAUSE_NODE`)
  - `once` (BOOLEAN, default true: act once per node run)
- On `EventsMissed` it drops its state and does not act: it is fail-safe and never stops on stale data.
- Tests use a virtual-time `StepVerifier`.

## S7. Webhook supervisor type (plugins), security

`webhook` (category INTEGRATION), new module `plugins/supervisors/webhook`.

- Settings:
  - `url` (required), `headers` (MAP; values may be `${env.X}`), `secretHeaders` (SECRET MAP)
  - `events` (LIST of event types; default `InputRequested`, `NodeStatusChanged`)
  - `timeoutMs` (default 3000, max 10,000)
  - `review` (BOOLEAN: also POST reviews) and `reviewOnError` (ENUM `DENY` (default) or `ALLOW`)
  - `maxConcurrent` (default 4)
- Contract:
  - `POST {url}` with `{kind: "event"|"review", supervisorId, attachmentId, event | request}`, secrets masked.
  - A reply of `{commands: [...]}` is mapped through `SupervisorResultMapper`'s allow-list, at most 10 commands.
  - A reply of `{decision: allow|deny|requireApproval, reason}` is a review verdict.
- Egress: reuse `HostAllowList` and `EgressPolicy` from `plugins/processors/http-request/HttpRequestGateway`, moving them to `plugin-api` or a shared module if needed. Private addresses are refused unless they are allow-listed, and redirects are not followed.
- `defaultAllowedCommands` is empty: the supervisor may send nothing until `allowedCommands` names something, as for workflow supervisors.
- Tests use a mock server: timeout, a 5xx reply, a malformed reply, a disallowed command, an SSRF target refused, and a secret never logged.

## S8. Supervisors UI design (design artboards)

Before any UI code, add `design/plans/ui-redesign/artboards/Supervisors.html`, built from the same shared tokens and header as `Plugins.html` and `Control.html`, and link it from `index.html`. Screens:

1. **Catalog**:
   - type cards grouped by category, each with icon, name, one-line description, kind badge and "N configured";
   - a detail pane with description, usage, a settings table generated from the schema, the example, and a "Create supervisor" button.
2. **Supervisors list**: cards or rows with type icon, name, dry-run badge, attached-to count, a health donut (applied/rejected/recorded/limited) and a source badge (SYSTEM read-only).
3. **Supervisor detail** (`/supervisors/:id`): tabs Overview (settings shown read-only through the form), Attachments (grouped by source, with origin links), Decisions.
4. **Create/edit wizard**: (1) pick a type from the catalog, (2) settings via the schema form, (3) Permissions (allowed commands as chips, can answer, max risk, observe internal, dry-run), (4) review, with live validation. "Advanced: JSON" toggle.
5. **Attach dialog** (manual): scope radio, a session select, a **workflow select from that session**, and a **node picker grouped by id prefix** for sections; a scope preview; "Start now".
6. **Creation-time picker**: the Supervisors section of the session form and the Supervisors panel of the workflow editor (whole workflow or chosen nodes; "Create new" opens the wizard in a drawer and returns the id).
7. **Effective supervision card** on the session and workflow details pages: the supervisors that apply, with badges Definition, Manual, Inherited (session/global) and System, plus Configure and Attach manually.
8. **Control summary tile**: active count, conflicts or flapping in the last hour, decisions per minute, a link to the page.

Include light, dark and 390 px phone views, and the empty, loading and error states (`StateMessage`).

## S9. Supervisors page: navigation and catalog (ui)

- Route `/supervisors` (lazy) in `routes/router.tsx`. Nav entry in `components/layout/AppHeader.tsx` `NAV` (icon `shield_person`; run `pnpm run icons`) and in `CommandPalette.tsx`.
- `features/supervisors/` contains `api/` (types, supervisors, attachments, decisions), `hooks/` (TanStack keys `['supervisors', ...]`), `components/SupervisorsPage.tsx` (tabs Catalog, Supervisors, Attachments, Decisions, with the tab in `?tab=` as on `ControlPage`) and `CatalogTab.tsx`. The list/detail layout copies `features/plugins/components/PluginsPage.tsx`.
- Vitest and MSW tests, and a Playwright spec with screenshots like `plugins`. `yk tool -C ui` green (100% coverage thresholds).

## S10. Supervisors page: manage supervisors and attachments (ui)

- A list tab and `/supervisors/:id` detail. Move `SupervisorCard`, the donut and the decision list from `features/control/supervisors/` into `features/supervisors/` and adapt them.
- Wizard `/supervisors/new?type=` and `/supervisors/:id/edit`: reuse `features/builder/components/ConfigForm.tsx` for the type schema and the common schema, with live `POST /api/supervisors/validate`. Secrets come back as `***` and are only sent when changed.
- An Attachments tab with filters (source, scope kind, session) and the redesigned `AttachDialog` (workflow select, node picker from the workflow's nodes). DEFINITION rows are read-only and link to their origin.
- A Decisions tab: the global feed with supervisor and outcome filters.
- Delete `ProfileDialog.tsx` and the old raw-JSON form.

## S11. Supervisors at session and workflow creation (ui)

- `SupervisorPicker` component (multi-select of configured supervisors, with "Create new" opening the wizard in a `Sheet`).
- `SessionFormPage.tsx`: a "Supervisors" section after Tags, bound to `supervisors` in the saved session.
- `NewWorkflowDialog` (in `SessionDetailsPage.tsx`): an optional picker.
- `WorkflowEditorPage.tsx`: a "Supervisors" panel. Each ref is "Whole workflow" or "Selected nodes" (a node picker from the draft's nodes, grouped by prefix). It is part of the draft, so `LeaveGuard` and Save version cover it. Validation errors map to the panel.
- Replace `ScopedSupervisors.tsx` on the workflow and session details pages with the effective supervision card (S8 §7), backed by `GET /api/supervisors/attachments?session=&workflow=`.
- `importSession.ts` and the templates keep `supervisors`.

## S12. Control page summary tile and cleanup (ui, docs)

- Replace `SupervisorCockpit` in `ControlPage.tsx` with `SupervisorsTile` (S8 §8). Remove the leftovers under `features/control/supervisors/` and `features/control/api/supervisors.ts`.
- Docs:
  - rewrite `docs/running/supervisors.mdx` around the catalog, supervisors, attachments and definitions, with one subsection per built-in type generated from its schema;
  - update `docs/running/control-dashboard.mdx`, `core/.claude/rules/supervisors.md`, `docs/contributing/core-reference.mdx` (then run `core/scripts/check-core-reference.sh`), and `ui/CLAUDE.md` if new patterns are added.
- E2E: create a supervisor in the wizard, declare it in a new workflow, see it as effective on the workflow page, and see its decisions.

## Dependency order

`S1` → `S2` → `S3` → `S4`; `S5`, `S6` and `S7` after `S1` (they can run in parallel with S2-S4); `S8` any time, before `S9`; `S9` after `S3`; `S10` after `S9`; `S11` after `S4` and `S10`; `S12` last.

## Issues

Epic: #1996.

| § | Issue | Title |
|---|---|---|
| S1 | #1997 | Supervisor type descriptor and config schema |
| S2 | #1998 | Rename to supervisors and attachments with a source |
| S3 | #1999 | Supervisor API v2, MCP tools, CLI (breaking) |
| S4 | #2000 | Supervisors declared in session and workflow definitions |
| S5 | #2001 | failure-guard and approval-gate types |
| S6 | #2002 | stall-watchdog type |
| S7 | #2003 | webhook type (security) |
| S8 | #2004 | Supervisors UI artboards |
| S9 | #2005 | Supervisors page, navigation and catalog |
| S10 | #2006 | Manage supervisors and attachments |
| S11 | #2007 | Supervisors at session and workflow creation |
| S12 | #2008 | Control tile, docs, end-to-end test |
