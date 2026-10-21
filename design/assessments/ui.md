# UI: assessment

How the `ui` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/ui.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Aui), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Aui) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Aui) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

1. **Today.** A same-origin SPA served by boot. Streams and REST work with no key. Builder, plugins, history and control pages exist; unit tests run only by hand.
2. **Make it usable on a protected server.** Choose a browser auth model, make fetch and the streams carry it, and show a clear 'sign in' state instead of 'server not running'. Risks: `noAuthHeader`.
3. **Safer actions.** One idempotency key per click, no automatic retry of non-idempotent calls, per-run stop modes, a draft guard and a version check on save. Risks: `startRetryKey`, `stopAllRuns`, `noDraftGuard`, `wholeSessionSave`.
4. **Use the event model fully.** Resume streams with Last-Event-ID, switch to /api/executions/... paths and drive the Control page from the global event stream. Risks: `replayNoLastId`, `logDedupe`, `legacyAliases`, `pollControl`.
5. **Tighten the contract.** When core publishes a status enum, generate unions, take icons from plugin metadata and run the unit and type-drift checks in CI. Risks: `freeStatus`, `iconGap`, `noTestGate`, `deadSanitiser`.
6. **Close the REST gap.** Add delete, run search, paging and resume as operators ask for them (approvals and versions are done). Risks: `uiGaps`, `pagingCaps`.

## Quality lenses

### Scalability

The static files scale trivially (served by boot, cached for a year when hashed). What scales poorly is per-user load on the server and per-page work in the browser: every open page holds streams and some pages poll.

**Ceilings today**

- Two long-lived streams per open run page; browsers limit connections per host
- Control page polls two endpoints every 5 s per browser
- Session list loads every session summary and pages in the browser
- Large graphs (the server allows 500 nodes) mean hundreds of animated canvas nodes

**Levers today**

- Hashed, long-cached assets
- Log buffer and virtual list keep memory bounded
- 5 minute cache avoids repeat fetches

**Needs design work**

- Use the global events stream instead of polling for Control
- Server paging for sessions and runs
- Level-of-detail or collapsing for very large graphs

### Performance

A small first load (lazy routes, split vendor files, a 13.7 KB icon font) and cheap live updates (batched logs, virtual list, whole-snapshot progress).

**Ceilings today**

- Every progress frame replaces the whole snapshot and re-lays out the graph
- Reconnect replays the full history of a run
- Largest single script about 257 KB; total first load for the run view is several hundred KB
- Dedupe and filtering touch up to 2,000 strings per update

**Levers today**

- Build-time budgets per chunk and for the icon font
- 100 ms log batching
- Fixed-height virtual rows

**Needs design work**

- Use Last-Event-ID so reconnects resume instead of replaying
- Re-layout only when the graph changes, not on every status frame
- A speed budget (not only a size budget)

### Availability

The UI is stateless, so it recovers by reloading. Its job is to degrade clearly when the server is away or a stream drops.

**Ceilings today**

- Run page shows a blank error message with no retry if the graph or run list fails
- Streams reopen only while the run is not finished; a long outage during a run relies on the 30 s backoff cap
- An unsaved builder draft is lost on any reload
- No offline or stale-while-error view beyond the 5 minute cache

**Levers today**

- Stream reconnect with backoff
- Error states with retry on most lists
- Same-origin deployment, so nothing else to be down

**Needs design work**

- Retry on the run page
- Unsaved-changes guard or local draft storage
- A visible 'live updates paused' indicator

### Security

Browser-side defences are reasonable (CSP, URL-encoded ids, no HTML injection paths in the code). The larger gaps are that the app cannot authenticate and shows every control to everyone.

**Ceilings today**

- No credentials sent: unusable once yukta.api-key is set
- EventSource cannot carry headers, so streams cannot use the same scheme
- No role awareness: every visitor sees Stop, Kill and Save
- CSP allows inline styles (needed by React Flow)

**Levers today**

- CSP: same-origin scripts and connections, no framing
- Ids URL-encoded
- Secrets entered as decrypted:&lt;key&gt; placeholders, not values

**Needs design work**

- A login or key flow plus a stream transport that can authenticate
- Role-based hiding of destructive controls once the server has roles
- Remove or use the unused sanitiser dependency deliberately

### Operability

Good visibility of runs, weak visibility into the UI itself. Failures are shown to the user but not reported anywhere.

**Ceilings today**

- No client-side error or performance reporting
- Unit tests do not run in ':ui:check' or CI as far as I found
- Icons and API types need manual commands (network, running server)
- No pages for resuming an aborted run, breakpoints, scheduler limits or priority

**Levers today**

- Control page with supervisor counters and heartbeats
- CI E2E job with screenshot baselines
- Build guards for size, headers and version

**Needs design work**

- Run Vitest in CI and enforce the coverage threshold
- CI step for the API types check
- Surfaces for the operations that exist only in REST

### Evolvability

Feature folders, one API client and one status mapper keep change local. Weak points are hand-maintained mappings (statuses, icons) and legacy endpoint paths.

**Ceilings today**

- Statuses are free strings, so the mapper can drift from core
- Plugin icon map must be edited for each new plugin
- Deprecated /api/workflow/... paths are hard-coded in hooks and E2E mocks
- Generated types are all-optional, so hand-written types duplicate the contract

**Levers today**

- Compile-time check that hand-written and generated types agree
- Schema-driven forms need no change per plugin
- Design tokens in one CSS file

**Needs design work**

- Status enum from core, then generated unions
- Icon (and colour) from plugin metadata instead of a UI-side map
- Move to the /api/executions/... paths

## Questions to settle

- **Security.** Your server is exposed to the team and a key is required. Walk through what the first screen shows and how an operator recovers. What would you build? (risk `noAuthHeader`)
- **Reliability.** A start request times out in the browser but the server accepted it. What does the user see, how many runs exist after the retry, and how would you prove it? (risk `startRetryKey`)
- **Performance.** A run has produced 200,000 log lines and the connection drops every minute. What does each reconnect cost the server and the browser? (risk `replayNoLastId`)
- **Correctness.** Core adds a status WAITING_APPROVAL. Which screens change meaning, and how would you find out before a user does? (risk `freeStatus`)
- **Operability.** Two operators press Drain on the same workflow while a third is debugging a run of it. Who is surprised, and what should the dialog say? (risk `stopAllRuns`)
- **Data.** Two people edit different workflows of one session. Whose change survives, and where would you add the check? (risk `wholeSessionSave`)
- **Scale.** Fifty operators leave the Control page open all day. What is the steady load, and would you move to events or slow the poll? (risk `pollControl`)
- **Governance.** A UI regression ships that only a unit test would catch. Which gate should have stopped it? (risk `noTestGate`)
- **Product.** A plugin author wants a custom icon and a rich node card. What belongs in the plugin's metadata and what in the UI, and is rendering their HTML ever acceptable? (risk `deadSanitiser`)
- **Evolution.** The server drops its legacy /api/workflow/... status paths next release. What breaks first, and how would you notice in CI? (risk `legacyAliases`)
