# UI redesign: the parts that need more than a restyle

**Status (2026-10-30, end of the first session).** The page-group PRs (#1468–#1483) are merged. Most of the work items below are built: each is its own PR, which passed `yk check` and has 100% unit-test coverage. The table says where each one stands. Before starting anything, check the live state with `gh pr view <n>`, because PRs merge while work goes on.

| Item | PR | State on 2026-10-30 | Notes |
|---|---|---|---|
| U2 Recover on Runs | #1479 | merged | |
| C1 limit tiles | #1480 | merged | live-nodes table still open (decision Q3) |
| P1 tabs, Copy node JSON | #1481 | merged | |
| N1 New session (restyle parts) | #1482 | merged | path validation still open (Q4) |
| D3 "Yukta" wordmark | #1483 | merged | |
| D1 server: node `group`, `subWorkflowId` | #1487 | merged | |
| R1 canvas + R5 drill-down | #1488 | merged | |
| R1 task list + B1 builder | #1490 | open, base main | |
| R3 progress tabs + failure callout | #1492 | open, stacked on #1490 | |
| R4 follow running nodes | #1504 | open, stacked on #1492 | |
| S1 last run per session | #1496 | open, base main | |
| S3 relative times + Last run filter | #1505 | open, stacked on #1496 | |
| U1 run progress + counts | #1498 | open, base main | |
| B2 validation warnings (unread ports) | #1502 | open, base main | rule changed from the plan: see B2 |
| P1 "Used in" + `/api/plugins/{type}/usage` | #1503 | open, base main | |
| S2 per-row Run + session Copy as | #1506 | open, base main | |
| R2 log level + node filter | #1507 | open, base main | Events tab still open (Q2) |
| B4 unsaved changes + Discard | #1508 | open, base main | |
| K1 palette actions | #1510 | open, base main | |
| B3 inspector tabs | #1511 | open, base main | |
| B4 drag-to-connect | #1512 | open, base main | |
| K2 breadcrumbs inside pages | none | not started | waiting on Q1 |

**Stacks:** when a base PR is squash-merged, rebase the next PR onto main with `git rebase --onto origin/main <old-base-branch> <branch>`, then force-push. GitHub moves the PR's base to main by itself. A PR in the merge queue can't be pushed to.

**Likely conflicts:** #1508, #1511 and #1512 all change `WorkflowEditorPage.tsx`. Rebase whichever merges later.

**Design references**
- **Offline:** [`artboards/index.html`](artboards/index.html) has a static copy of every artboard. Fonts, icons and the logo are in `artboards/assets/`, so it opens with no network. Add `#dark` or `#light` to any URL to switch theme.
- **Live canvas:** https://claude.ai/artifact/FDLDwU5fDE6XqogqKQzqNQ (editable).
- **Design system:** https://claude.ai/artifact/LeqMJNrp9wEkCaEDrKAWch. Its README has the status vocabulary and the agent-friendly rules.

## Decisions (made 2026-10-30)

- **D1:** declared `group` on a node, with the node-ID prefix as the fallback. The server part (the `group` field on a workflow node, passed through by the graph endpoint) is in progress on `feat/node-group`.
- **D2:** no "Healing" label. Healing is not a Yukta feature; it came from the dogfood example's own `*.heal` sub-workflows. The stage aggregate is Failed / Running / Succeeded / Waiting only, and R3's callout is about a failed node in general, not a heal.
- **D3:** the wordmark is "Yukta" (#1483, which also fixes the nested `<button>` in `HeaderLogo`).

The original options are kept below for the record.

## Decisions needed first (resolved above)

1. **Stage grouping rule (D1).** This blocks R1 and B1. Two options:
   - **ID prefix:** group nodes by node-ID prefix (`ui_prettier`, `ui_prettier.fail`, `ui_prettier.heal`, `ui_prettier.failed`). This needs no server change, but it is only a naming convention: workflows that don't follow it show ungrouped nodes.
   - **Declared groups:** add an optional `group` field to a node in the workflow JSON, with core and `WorkflowGraph` passing it through. This is explicit and works for every workflow, but it changes the core model and the plugin API docs.

   *Recommendation:* declared `group`, with the ID prefix as the fallback when it's absent. Every existing workflow then groups on day one, and new ones can say what they mean.
2. **"Healing" label (D2).** A stage whose node failed while its heal sub-workflow runs shows "Healing" on the artboard. That label is not in the approved status vocabulary.
   - *Recommendation:* keep it as a **stage-level** label only (derived, never a backend status). Add it to the design system's Run status table as an aggregate.
3. **Wordmark (D3).** The header logo text is "YUKTA", but the artboards say "Yukta". This is a brand call.
   - If you choose "Yukta", also fix `HeaderLogo`, which nests a `<button>` inside a `<Link>`; that isn't valid HTML.

## Work items

Each item lists: the artboard, what it adds, why it isn't a restyle, the approach, and the tests.

### Run view (artboard: Run view · check)

**R1. Stage grouping on the canvas and in the task list.** Blocked by D1.
- **What it adds:** collapsed stage boxes show their node count and an aggregate status. The failing stage expands inline, and the task list is grouped by stage.
- **Why it's not a restyle:** it needs a grouping model, new layout logic, and an aggregate-status rule.
- **Approach:**
  - Add `groupNodes(graph): Group[]` in `features/workflow/lib/`.
  - Write the aggregate rule (D2: no Healing):
    - any failed → Failed
    - else any running → Running
    - else all succeeded → Succeeded
    - else Waiting
  - Make `layoutGraph` lay out groups as compound nodes (dagre supports clusters), with an expand state stored in the URL (`?open=ui_prettier`).
  - Add a "Group by stage / All nodes" toggle.
- **Tests:** unit tests for `groupNodes` and the aggregate rule (one per branch). A canvas test for expand and collapse. An e2e test that opens a failed stage from the URL.

**R2. Logs drawer.**
- **What it adds:** a level filter (All / Info / Warn / Error), a Follow toggle, a node filter chip ("Node: ui_prettier*", set by clicking a node), and an Events tab.
- **Why it's not a restyle:**
  - The level filter and Follow: check whether `LogsPanel` (357 lines) already has them; if not, they're new state.
  - The Events tab needs a source. The control-bus activity feed (`features/control`) or the execution's status transitions both could serve; pick one.
- **Tests:** filtering by level and by node. Follow stays pinned to the bottom when new lines arrive.

**R3. Progress panel tabs (Progress / Result / Details) and a failure callout.**
- **What it adds:** the panel today shows the summary, result and task list in one column. The artboard splits them into tabs and adds an attention callout. Per D2 the callout is generic ("ui_prettier failed", with a link to the node and its error), not a heal message.
- **Tests:** the callout appears for a failed node and names it; it is absent when nothing failed.

**R4. "Follow running node".**
- **What it adds:** a canvas toggle that keeps the running node in view as the run progresses. Today the canvas only fits to running nodes when it opens (#1472).
- **Approach:** use `useReactFlow().fitView({ nodes })` whenever the set of running nodes changes, while the toggle is on.

**R5. Drill down into a sub-workflow.** Requested 2026-10-30; not on an artboard.
- **What it adds:** a `SUB_WORKFLOW` node on the canvas offers "Open sub-workflow". On a run, it opens the child run. Without a run, it opens the workflow definition. The child's run view gets a "Back to parent run" link.
- **Data:** the graph endpoint now names the workflow a sub-workflow node runs (`subWorkflowId`, on `feat/node-group`). The child run comes from `GET /api/executions/{id}/tree`: the child of this run whose `workflowId` is the node's `subWorkflowId`. The parent link comes from `parentExecutionId`.
- **Ceiling:** two sub-workflow nodes that run the same workflow can't be told apart from the tree alone. Fix: record the parent node ID on the child run, or read `executionId` from the node's output.
- **Tests:** the open action on run and definition views, the back link, and a node with no matching child run (the action is disabled).

### Builder (artboard: Builder · check)

**B1. Grouping in the outline and canvas.** Blocked by D1. Reuse `groupNodes` from R1.

**B2. Inline validation warnings with a fix action.**
- **What it adds:** e.g. "`ui_prettier` has no `timeout`; a hung process blocks the gate", with a **Fix it** action.
- **Why it's not a restyle:** `POST /api/sessions/validate` returns only `valid` plus one `message`. Warnings need a list of `{nodeId, field, severity, message}`.
  - **Server:** extend the validation response with `warnings[]`. Start with one rule: a `PROCESS_EXECUTOR` with no `timeout`.
  - **UI:** list the warnings; "Fix it" selects the node and focuses the field.
- **Tests:** the server test for the rule, the UI test for focus.

**B3. Inspector tabs: Config / Connections / Policy / JSON.**
- **What it adds:** splits the existing `NodeInspector` content into tabs. Policy is the existing engine panel. Connections lists the node's incoming and outgoing edges by port, with add and remove (moved from the Edges tab). JSON is the raw node, editable.
- **Why it's not a restyle:** editing edges per node is new interaction. The JSON tab needs the existing `JsonValidityContext` to report parse errors.
- **Tests:** edit an edge from Connections; an invalid JSON edit blocks Save.

**B4. Toolbar on the canvas: Add node / Connect / Tidy layout, and an unsaved-changes indicator with Discard.**
- **Why it's not a restyle:**
  - Connect means drag-to-connect on the canvas. Today the canvas is read-only (`nodesConnectable={false}`).
  - Unsaved changes need a dirty check against the loaded draft.
- **Approach:** start with the dirty indicator and Discard, which are cheap. Add drag-to-connect separately.

### Sessions (artboards: Sessions, Session · dogfood)

**S1. Last run per session from the server.**
- **Why it's not a restyle:** today the list scans the newest 200 runs (`ponytail:` note in `SessionListPage.tsx`). A session whose last run is older shows "—".
- **Server:** add `lastRun {executionId, workflowId, status, startTime}` to `/api/sessions/summaries`.
- **UI:** read it and drop the scan.

**S2. Per-row Run on the workflows table, and session "Copy as".**
- **Why it's not a restyle:** Run reuses the start-run mutation from `RunControls`, and needs a confirmation for workflows that take input. "Copy as" reuses `lib/commandEquivalents.ts` (`toCli` / `toCurl` / `toMcp`), which needs a session-level `CommandAction`.

**S3. Relative times ("4 min ago") and a "Last run" filter.**
- **What it adds:** a small `formatRelative` helper (tested), plus a status filter applied to S1's `lastRun`.

### Runs (artboard: Runs)

**U1. Summary chips (running / queued / failed in 24 h) and a per-run progress column (11/100 nodes, "failed at spotbugs").**
- **Why it's not a restyle:** `/api/executions` returns only `Hit(sessionId, executionId, workflowId, status, startTime, endTime)`.
  - **Server:** add `nodesDone`, `nodesTotal` and `failedNode` to `Hit`, plus a counts endpoint, or `counts` in the response.
  - **UI:** chips act as status filters in the URL.

**U2. Inline Recover for interrupted runs.**
- **What it adds:** a Recover button on rows with status `ABORTED`. It reuses `recoverExecution` from `features/workflow/api/executionControl.ts`.
- **Why it's not a restyle:** it's new row actions, with the permission gate and error display that `RunControls` already has.

### Plugins (artboard: Plugins · Process executor)

**P1. Ports / Example / Used in tabs, and Copy node JSON.**
- **Ports:** data exists (`outputPorts`); this is a layout change only.
- **Example:** `usagePattern` exists. The artboard implies a JSON node example, which could be built from `configSchema` defaults.
- **Used in:** which workflows use the plugin.
  - *Option A:* scan every session's workflows client-side (N requests).
  - *Option B (recommended):* a server endpoint `GET /api/plugins/{type}/usage`.
- **Copy node JSON:** client-only, from the `configSchema` defaults.

### Control (artboard: Control bus)

**C1. Limit tiles, a live-nodes table and "Who did what" results.**
- **What exists:** the data mostly exists already. `SchedulerPanel` has the limits, running count and queued count. `ControlBusPanel` lists nodes and fetches one heartbeat at a time.
- **Why it's not a restyle:** a live-nodes *table* with heartbeat age per row needs one heartbeat per node, so N requests on every poll. Either batch them on the server, or show age only for the selected node.
- **Approach:** the limit tiles are a restyle and can go first. The table waits on that server decision.

### Shell (artboard: Command palette)

**K1. Context actions in the palette:** Stop run, Copy MCP call, Copy CLI command.
- **Why it's not a restyle:** the palette needs the current page's context (session, workflow and run). Stop needs the confirm dialog and permission gate from `RunControls`. The copy actions reuse `commandEquivalents`.

**K2. Breadcrumbs inside each page.**
- **Why it's not a restyle:** this is a cross-page change. The global `BreadcrumbNav` row would be removed and each page would render its own trail.
- **Approach:** do it after D3. Once no page sets `useBreadcrumbOverride`, delete `lib/breadcrumbOverrides`; the run view (#1472) still uses it today.

### Not built yet

**N1. New session (page group 6).** Not started. It is the one artboard with no PR.
- **Restyle parts:**
  - the session ID derived from the name
  - a primary Create button
  - `key: value` tags (matches `create_session`'s `tags` map)
  - the "Same thing, scripted" panel (MCP / curl), via `commandEquivalents`
- **Needs more:** inline project-path validation ("The server can't find …") requires a server check, either a `validate` call or a dry-run.

## Open questions for the user

1. **Q1, K2:** move the breadcrumbs into each page and remove the global `BreadcrumbNav` row? It changes every page and most screenshots.
2. **Q2, R2 Events tab:** what source should it use, the control-bus activity feed (`features/control`) or the run's status transitions?
3. **Q3, C1 live-nodes table:** batch every node's heartbeat in one server call, or show the heartbeat age only for the selected node?
4. **Q4, N1 project-path check:** add a server endpoint that checks the path exists on the server?
5. **Q5, the "all ports" bug** (found 2026-10-30, not fixed): in the builder, the edge port option "all ports" saves `sourcePort: null`. `AppConfigMapper` and `SessionMapper` then turn that into `"default"`, and the engine (`StreamTopologyDecorator`) reads only the `default` port. So on a multi-port node, "all ports" doesn't do what it says. Fix it (keep `null` through the mappers, and check nothing else relies on `"default"`), or relabel the option? File an issue either way.

## Changes from the plan made while building

- **B2:** the plan's rule ("`PROCESS_EXECUTOR` has no timeout") is false, because an unset timeout is 300 s. The user chose a different first rule: on a node with several output ports, warn about a port no edge reads from.
- **R3:** per D2, the callout is a plain failure (first failed node, first line of `metadata.error`, how many more failed, and a link into the sub-workflow run), with no heal wording.
- **U1:** `failed` in the counts means status FAILURE only, so it matches the Runs filter `status=FAILURE&range=24h`.
- **N1:** Description stays required, because the server's `@NotBlank` rejects a blank one.
- **R4:** follow is capped at 100% zoom, so one running node is panned to without being magnified.

## How to work on this (lessons from the first session)

- **Worktrees:** work in a git worktree off `origin/main`, one branch per item, and open one PR per item. The main checkout has another session's uncommitted `ui/src/features/workflow/lib/layout.ts`, so don't touch it. In a new worktree, link the dependencies with `ln -s <main checkout>/ui/node_modules ui/node_modules`.
- **Running yk from a worktree:** run `yk check` and `yk plan` as `./examples/11-dogfood/yk …` from inside the worktree. The `yk` on PATH resolves to the main checkout. `yk gradle` and `yk tool` already follow the current directory. Start `yk check` in the background, and don't edit that worktree while it runs. For a stacked PR, add `--base origin/<parent-branch>`.
- **Prettier:** run `prettier --write` on the files you changed only, never on a directory, because many files on main aren't prettier-clean. The gate's `ui_prettier` fix rewrites untouched test files and `src/types/api.generated.ts`. Revert those with `git checkout ui`, and keep the gate's Java style and spotbugs fixes after reading them.
- **Reading vitest results:** `yk tool` hides output on success. Use `--reporter=json --outputFile=/tmp/x.json` to get test counts. Coverage is 100% on all ui code: find gaps with `--coverage.reporter=json`, then read `coverage-final.json`. A FAILED result with every test passing usually means an *uncaught exception*. It's typically `userEvent` on the xyflow canvas leaving a pointer event after jsdom is torn down, so use `fireEvent` for canvas clicks.
- **API changes:** refresh the snapshot with `yk gradle :boot:test --tests '*OpenApiSnapshotTest' -Pyukta.openapi.update=true`. That rewrites `ui/openapi.json` with sorted keys, so put it back in the committed order with `python3 design/plans/ui-redesign/tools/specmerge.py <old.json from git show HEAD:ui/openapi.json> ui/openapi.json`. Then regenerate the types with `yk tool -C ui node scripts/generate-api-types.mjs openapi.json`. The diff should then be only the new fields.
- **Spotless:** if root `spotlessApply` fails on `spotlessKotlinGradle` (a config-cache problem), use the module-scoped tasks, e.g. `:web:spotlessApply`.
- **Icons:** the icon font is a subset. Use a Material Symbols name that already appears somewhere in `src`, or regenerate the font with `pnpm run icons` (needs network).

## Housekeeping

- The chromium screenshots for `workflow-details` on main differ on every text glyph in a local run, regardless of the change being tested, so they and the Firefox/WebKit baselines need regenerating in CI. Update a screenshot locally only for a change you've made, and open the image before updating the baseline.

- The Firefox and WebKit `session-list-*` screenshot baselines fail on `main` as well. Regenerate them in the CI environment.
- Under coverage load, the full vitest run sometimes times out `RunsPage` "loads more…" and `SessionDetailsPage` "renders…" at 5 s. They pass on rerun. Raise their timeout or find what's slow.

## Verification, for every item

- Run `yk gradle :ui:pnpmTest`; coverage thresholds are 100%.
- Run `yk check --base <parent branch>`.
- Run Playwright on chromium. Open each changed screenshot before updating its baseline.
- Compare the result against the matching artboard in `artboards/`.
