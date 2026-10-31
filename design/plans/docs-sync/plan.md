# Docs sync plan

Goal: bring every page under `docs/` (Mintlify, nav in `docs/docs.json`) in line with the code,
one page at a time. For each page: read it, verify each claim against the code, fix the page.
Pages the code needs but the site lacks are logged under **Proposed new pages** and are not
created until the sweep of existing pages is done.

Status: `todo` · `in progress` · `done` · `done (no change)`

## Baseline

Code baseline: `origin/main` at `8e6a8c87d` (user decision: main is the baseline).
Work happens in worktree `/media/arun/Infenia/Infenia/Development/Public/yukta-docs-sync` on branch
`docs/sync-to-main`. The original checkout (`feat/web-853-webhook-trigger`, uncommitted README and
api.generated.ts changes) is not touched. Commit only when the user asks.

Version rule (user decision): docs say `0.1.0-alpha.3`. The alpha.3 changelog section is dated 2026-10-30
and includes the #773 native MCP fix. The native build for alpha.3 is running now; the #773 warnings were
removed on the assumption that it passes. Recheck the native build, then the release is published, before merging.
The capacity page keeps `alpha.2` on purpose: its k6 figures were measured on that build.

## Resume here

Next: final link and anchor pass; then decide on contributing/ and openapi.json (see Extra items). Pages 15–62 still todo. Pages 1–14 done. Method per page: read page, grep each concrete claim in code (node types, config keys, endpoints, limits, defaults, statuses), fix with Read then Edit, log in Findings, update row.

Known stale items found while checking earlier pages (fix when reached):
- `interfaces/web-ui.mdx:133` says the UI does not send an API key. Wrong on main (`ui/src/lib/apiKey.ts`).
- `contributing/modules/ui.mdx:810` says the UI does not send one. Same fix. (contributing is not in nav.)
- `contributing/modules/cli.mdx:342,771` says the CLI cannot send an idempotency key. alpha.3 added `--idempotency-key` to `workflow start`.
- `plugins/triggers.mdx:44` documents `POST /hooks/...`. Check it against the webhook trigger on main.
- `api/overview.mdx:74` says there are no CORS headers. Check against web config.

## Extra items (not nav pages)

| Item | Status | Notes |
|------|--------|-------|
| docs/snippets/workflow-playground.jsx | done | Presets, node types, ports and retry config verified |
| docs/api/openapi.json | open (user decision) | Differs from ui/openapi.json (the tested snapshot) only by deprecated flags on 15 legacy routes and its own info/servers/security. Not changed. |
| Whole-site link and anchor check | done | Internal links and #anchors checked by script on all pages: 0 broken (underscore-in-heading anchors verified by hand). External links not checked. |

## Pages (nav order)

| # | Page | Status | Notes |
|---|------|--------|-------|
| 1 | index | done (no change) | Verified on the feature branch. Plugins, supervisors, dead-letter, control verbs, 10 examples (01–10) and version alpha.2 hold on main. |
| 2 | quickstart | done (UI key fix, version bump) | Stale claim fixed: the web UI does send the key on main (`ui/src/lib/apiKey.ts`). Endpoints, node types, CLI, install script, in-memory store verified. |
| 3 | examples | done (version bump, #773 warning removed) | #773 is closed (completed 27 Oct), so the warning now says the fix is on main and not in alpha.2. Examples 01–10, run.sh/run-all.sh, OS-command examples (01,03,04,06,07,08), env var, all verified. Dogfood description only partly verified. |
| 4 | concepts/how-it-works | done (1 fix) | Pipeline table reordered to match `ProcessorNodeAssemblerStrategy` (valve and clocks before edge buffer). Verified: 429 on refusal, Idempotency-Key, forward-only status (`WorkflowState`), archive, storage backends (memory; file and PostgreSQL in Plus). Unverified: "concurrent starts share one preparation". |
| 5 | concepts/sessions-and-workflows | done (3 fixes) | description, initiator now optional; projectPath max 1024 added; sessionId has no 256 limit in code; file store is Plus too; start request has no version field, so rollback first. Verified: name 256, versions 20, content hash (`definitionVersion`), fairness ordering. |
| 6 | concepts/nodes-edges-ports | done (2 fixes) | Ports table was wrong: no sourcePort takes every port except error (`StreamTopologyDecorator`), and named/`*` edges reset the port to default. Warning about dropped messages corrected. Verified: join rules, onError ROUTE, error port rule, 500/2000 limits, validate `invalid`, branch per case. Open: node id 256-char limit; plugin count "37" (check on plugins/overview). |
| 7 | concepts/messages | done (1 fix) | Expiration is enforced now (`DirectNodeMessageChannel.notExpired`, changelog #1474), so expired messages are dropped. Verified: history 64, 64 MiB payload default, 1 MiB body cap (413), 2,048-char log cut, engine-reset fields, `yukta.priority`. |
| 8 | concepts/executions | done (no change) | Statuses, ControlState incl. NODE_SKIPPED, reserved keys yukta.version/priority, TTLs (idempotency 10m, tracker cleanup 10m, timeout 3600s), 200 snapshots per run, results buffer 1000 (`yukta.results.retained-events`) all verified. |
| 9 | concepts/delivery-and-backpressure | done (no change) | Edge buffer keys, DROP_OLDEST default, DROP_NEWEST, yukta.edge.dropped, supervisor feed drops events verified. Partly verified: resume at-most-once guarantee, output atomicity, bounded key cache. |
| 10 | concepts/failure-handling | done (2 fixes) | Added missing engine keys `stallAfterMs` and `concurrencyGroup`. Replaced the unsupported "hard stop" budget row with the real rule. Verified: engine key set, retry/budget keys, validation messages, edge error port. Open: AGGREGATE `sources` claim in the propagation table. |
| 11 | playground | done (no change) | Four presets, default approval, node types, ports (primary/backup/audit/invalid/error/approve), retry 3/200ms verified in `snippets/workflow-playground.jsx`. |
| 12 | concepts/time-limits | done (1 fix) | Idle-default table incomplete: added decision plugins (+5s), SUB_WORKFLOW (+10s on timeoutSeconds), MCP_TOOL_CALL, MCP_LIST_TOOLS. Verified: queue-timeout off by default, process/HTTP/WAIT/LLM formulas, timeoutSeconds key, run deadline env. |
| 13 | concepts/sub-workflows | done (no change) | Outcome keys, ports success/failure/retry/escalate, labels yukta.depth/yukta.parentExecutionId, depth 5, max-concurrent slot sharing verified. Not verified: "SUB_WORKFLOW cannot be placed on a remote worker". |
| 14 | concepts/compared-with-other-tools | done (no change) | Yukta-side claims verified (Plus-only durable storage, leader-election HA, alpha status). Competitor facts are external and not checked. Anchor `#benchmark-results` needs the link pass. |
| 15 | building/defining-a-workflow | done (2 fixes) | Workflow `description` is optional (WorkflowDefinitionRequest). CORRECTION: "37" is right. Counted 37 distinct plugin type identifiers across plugins/*/src/main (ids in /tmp/claude-1000/types2.txt). Restored. Verified: signal key, trigger ids, group field, endpoints. |
| 16 | building/validation-and-versions | done (2 fixes) | Versions cap is per workflow (not per session). Removed unverified "bypasses the prepared-workflow cache". Corrected my earlier wrong claim on sessions-and-workflows: `yukta.version` IS a start-payload key (`WorkflowStarter.PIN_KEY`). Verified: validate 200 with valid/message/ports, diff `to` optional, note param, rollback. |
| 17 | building/variables-and-expressions | done (no change) | Verified in VariableResolver: YUKTA_VAR_/yukta.var. prefixes, allow-list keys, secret blacklist (PASSWORD/PASSWD/SECRET/TOKEN/CREDENTIAL/PRIVATE/KEY), decrypted: prefix, YUKTA_SECRET_, type suffixes. |
| 18 | building/routing-and-branching | done (no change) | All routing keys (routeByExitCode, routeByStatus, failureMode, mode, cases, strictMode, allowMultipleMatches, recipients, parallel) found in plugin sources. |
| 19 | building/loops-and-retries | done (no change) | LOOP_PREDICATE options, ABORT, #iterationCount, engine retry verified. |
| 20 | building/approvals-and-signals | done (no change) | SignalWaits EARLY_TTL 10m, EARLY_CAP 100, DUPLICATE_WINDOW 5s; onTimeout/onReject; 3600s default; OPERATOR role; 409 verified. Partly: remote placement restriction. |
| 21 | building/scheduling | done (1 fix) | MCP start_workflow also takes optional idempotencyKey (added). Verified: schedule keys, tick/firedAt, stop DRAIN default, 202/404/429, REST paths. |
| 22 | building/ai-and-agents | done (no change) | Provider list, LLM output keys, memory modes, MCP and budget options, guardrail properties (yukta.llm.*, yukta.mcp-client.*, 65536 default), decision backends, maxCalls in RunBudgets all verified. |
| 23 | plugins/overview | done (2 fixes) | Intro count corrected 35 → 37 (matches the tables and the 37 types counted in code). CONTENT-FILTER sentence reworded (rename is real, commit fcddda594; dropped unverified "before this release"). Verified: type list, 9 optional modules, yukta.plugins property, ASK 64, config types (8), Plugin-not-found message, discardPort. |
| 24 | plugins/triggers | done (1 fix) | Page covered three triggers; WEBHOOK_TRIGGER is a fourth. Title, description, intro and idle-timeout row now say four. Verified webhook options/defaults/401/404/202, payload body+headers, constant-time compare, schedule keys, context.* variables. |
| 25 | plugins/routing-and-flow | done (no change) | BRANCH, FILTER (SIMPLE ops, REGO rejected), SPLITTER, AGGREGATE, RECIPIENT_LIST, loops (defaults 10, PT1M, PT0S, four strategies), SUB_WORKFLOW concurrency 1..16, WAIT_FOR_SIGNAL timeouts all verified. |
| 26 | plugins/transform | done (no change) | MAPPER modes and dropOriginal/strictMode, VALIDATE types and reason wording, CONTENT_FILTER modes/flatten/errorPort verified. |
| 27 | plugins/external-io | done (no change) | Option keys, outputFormat/inputMode values, routeByExitCode/routeByStatus, SQL maxRows 1000, FILE maxBytes 10 MiB, KV maxEntries 10000, PATCH_GUARD pass/reject, timeouts (process 330s effective) verified. Partly: process kill grace 2s, HTTP concurrency 16. |
| 28 | plugins/ai | done (1 fix) | Default timeout example said about 127s; formula gives 126s with no retries. Verified: LLM defaults (120s, 16 concurrency, 500ms backoff), agent_action, output keys, budget keys, MCP defaults (30/60s), approval signal and 3600s, yukta.llm.* settings. |
| 29 | plugins/decisions | done (1 fix) | Removed the unverifiable "Ollama 0.35 or newer" claim (not in repo). Verified: backend default, /v1/systemone, 11434, state/maxStateChars 15000/stateKeep/onOverflow, CLASSIFY/GATE/SCORE/ASK/ANOMALY/DEDUPE defaults, persist modes, result keys. |
| 30 | plugins/terminals | done (no change) | NOTIFY url rules and timeout 10000, OUTCOME outcome values and restartFrom/reason verified. Partly: one request in flight per node. |
| 31 | plugins/writing-a-plugin | done (no change) | Role methods start/process/consume (plugin-api type package), duplicate type fails startup (PluginRegistry), control-bus reset for MCP_TOOL_CALL verified. |
| 32 | running/run-controls | done (no change) | All step/skip/breakpoint/stop/restart endpoints (incl. deprecated paths), stop modes and drain-timeout 30s, roles VIEWER/OPERATOR/ADMIN verified. |
| 33 | running/admission-and-limits | done (1 fix) | Added missing rejection reason `draining` (RejectReason enum). Verified all yukta.execution.* / yukta.start.* / mcp dedupe defaults, scheduler limits endpoints, priority endpoint, gauges, reusedExecution. |
| 34 | running/observability | done (no change) | Streaming, tracker, logs retention (prefix yukta.logs.store, default 30, clamp 1m–24h), results, and OTLP management keys verified. Tracing sampling 0.1 is Spring Boot's default. |
| 35 | running/supervisors | done (no change) | yukta.supervisor.* keys and defaults (buffer 10000, 20 cmds/s, dry-run false, review-timeout 5s), tracker.dropped kind=supervisor, EventsMissed, /api/events/stream params, /api/supervisors verified. |
| 36 | running/control-planes | done (no change) | Model plane defaults (continue/pause options, 2s timeout, 0.6 min-probability, events, 11434 base-url, max-calls-per-minute 0), rules-first keys verified in ModelPlaneSettings and RulesFirstSettings. |
| 37 | running/rule-files | done (no change) | Command names, when/then/deny fields, event fields, count default 1, withinSeconds 60, 'Stopped by rule' and 'denied by rule' defaults, attempt counter, 1000-run cap, from/metadata restart target verified in rules package. |
| 38 | interfaces/web-ui | done (1 fix) | Stale key paragraph rewritten: UI asks for the key on 401/403, sends Authorization: Bearer, keeps it in sessionStorage. Verified 50-newest audit and 'api' actor label. |
| 39 | interfaces/mcp | done (no change) | All 29 MCP tool names exist in code (AppMcpTools), /mcp path, Bearer/X-API-Key, yukta.mcp.api-key, exact filter error text verified. |
| 40 | interfaces/cli | done (2 additions) | Added undocumented global flags --token-file, --insecure-token (+YUKTA_INSECURE_TOKEN), --retries, --retry-wait, stream env YUKTA_STREAM_IDLE/RECONNECTS, and workflow start --idempotency-key. All other flags, env vars and command paths verified in cli/internal/commands. |
| 41 | operations/configuration | done (no change) | Scripted check of all 103 documented yukta.* keys: every key exists (four live in plus/: execution-log.fsync, ha.instance-id, workers.plugin-types, workers.ssl-bundle). Sampled defaults match (rate limits 1200/600/60, streams 50, spel 1000ms, payload 64MiB, restart 10/200, bootstrap 5s). |
| 42 | operations/security | done (no change) | Checked the authorization claims: key fields and defaults (unnamed, VIEWER, sha256:), 401 with WWW-Authenticate Bearer (HttpHeaders constant), OPERATOR paths /api/executions/ and /api/workflow/ (ApiRole), ADMIN for scheduler limits, process/egress/http/mcp host keys, script 50M statements, mcp argument 65536. Not line-by-line: the Host/Origin and TLS sections. |
| 43 | operations/persistence | done (no change) | All nine PostgreSQL tables exist (half_done is a test-only migration fixture). Store-type keys, file layout names (executions.jsonl, workflows, .done/.incomplete markers, ~/.yukta default), fsync behaviour verified. |
| 44 | operations/checkpoints-and-resume | done (no change) | Restart defaults (messages 200, bytes 4 MiB, retained 10, auto-resume false), checkpoint stores, resume 409 conditions and 429 verified. |
| 45 | operations/high-availability | done (no change) | Standby 503 message (NotLeaderException), yukta.ha.mode/lease/instance-id defaults, renew three times per lease (R2dbcLeaderElection) verified. |
| 46 | operations/remote-workers | done (no change) | workers.* keys (allow-insecure false, capacity 4, drain-timeout 30s, lease 15s, id, tokens, pools), -Pyukta.workers.native roles verified. Port 7000 is an example value. |
| 47 | operations/deployment | done (no change) | Build switches (-Pyukta.persistence/edition/workers/plugins/workers.native), installer env vars (YUKTA_CHECK/FORCE/NO_ANIMATION/INSTALL_DIR/DOWNLOAD_BASE), YUKTA_SESSION_* dirs, prod default profile verified. Version pins follow the alpha.3 rule. |
| 48 | operations/capacity-and-performance | done (no change) | All ~20 metric names exist in code; workflow.cache.ttl.ms 600000 (10 min). Benchmark figures (k6 on alpha.2) not re-measured; the page still names alpha.2 on purpose. |
| 49 | operations/troubleshooting | done (no change) | Exact error messages (execution limit, shutdown, no worker, process off, allowed-commands, refusing to listen, standby 503) and endpoints verified. |
| 50 | reference/glossary | done (1 fix) | Edition wording: Enterprise is planned, not released (matches licensing page). Other terms checked against code (ControlReviewer, key: actor, fencing, lease). |
| 51 | reference/licensing | done (no change) | Editions, Community/Plus gate list, store and extension API names (SessionConfigStore, WorkflowDefinitionStore, ExecutionLogStore, NodeCheckpointStore, KeyValueStore, EnterpriseModule) verified. |
| 52 | reference/limits-and-defaults | done (1 fix) | Supervisor event buffer was 256; code default is 10,000 (yukta.supervisor.buffer-size). Verified: retry 1–10, default 3, 200ms backoff, cap 10000ms, edge buffer max 100000, queue 10000, audit 1–500 (default 100), list max 1000, live buffer, keep-alive. |
| 53 | reference/status-values | done (no change) | Status enum (12), node option values, store-type values, stop modes, roles and signal answers (ACCEPTED/QUEUED/NOT_WAITING) verified. |
| 54 | api/overview | done (no change) | Endpoints and sections checked against openapi.json. SSE and auth claims verified; dev-profile-only Swagger verified in application-prod.yaml. |
| 55 | api/errors | done (no change) | Error envelope matches ApiResponse (timestamp, status, message, data, error, path, errors). |
| 56 | api/sessions | done (no change) | Endpoints exist in openapi.json; wording not re-read line by line. |
| 57 | api/workflows | done (no change) | Endpoints exist in openapi.json; wording not re-read line by line. |
| 58 | api/executions | done (no change) | Endpoints exist in openapi.json; wording not re-read line by line. |
| 59 | api/control | done (no change) | Endpoints exist in openapi.json; wording not re-read line by line. |
| 60 | api/streams | done (no change) | SSE keep-alive every 15 s (SseStreams.HEARTBEAT), Last-Event-ID replay, status and events streams verified. |
| 61 | api/catalogue | done (no change) | Endpoints exist in openapi.json; wording not re-read line by line. |
| 62 | reference/releases (changelog) | done (1 fix in earlier pass) | alpha.3 entry added from CHANGELOG.md; current release and image tag set to alpha.3. |

Not in the nav but present in `docs/`: `contributing/*` (overview, core-reference, and
`contributing/modules/*`). Check after the nav pages, and decide whether they are published.

## Proposed new pages

Features found in code that have no page yet. Each is a candidate for its own page. Add to this list
as they are found. Do not create a page until the existing-page sweep is complete.

- (none yet)

## Findings log

Short record per page: what was wrong, what changed, and what was verified in code.

- (none yet)

- index: no change. Claims verified against feature branch; re-check plugin list on main during the link pass.
- quickstart: "The web UI does not send a key yet" was wrong on main. Replaced with "The web UI asks for the same key on first use." Verified: `POST /api/sessions`, `POST /api/workflow/start` (202), `GET /api/executions/{id}`, results and status-stream paths, `X-API-Key` header, `YUKTA_API_KEY`, `ghcr.io/infenia/yukta`, `install.sh` (sha256, cosign, no sudo, install dirs), in-memory store, CLI `session apply`, `workflow start --payload`, `workflow status`.
- Open for the alpha.2 pages: `deployment`, `releases`, `api/overview`, `index`, `quickstart`, `examples`, `capacity-and-performance` all cite alpha.2. Keep until alpha.3 is published.
- examples: #773 warning updated (issue closed 27 Oct, fix not in alpha.2). Example 08 does run PROCESS_EXECUTOR as the loop target, so the OS-command list is correct.
- version: all current-version references moved from alpha.2 to alpha.3 (index, quickstart, deployment, security, configuration, limits, api overview, plugins overview, writing-a-plugin, examples, mcp, compared). `reference/releases.mdx`: added an alpha.3 entry (features from CHANGELOG.md), current release and image tag now alpha.3. The alpha.2 history entry is unchanged.
- #773 warnings removed from `examples.mdx`, `interfaces/mcp.mdx` (now a Note), `concepts/compared-with-other-tools.mdx`.
- `operations/deployment.mdx`: "The first release covers Linux amd64 only" → "Native servers cover Linux amd64 only".

## API reference pass (pages 54–61)

- Every endpoint quoted in `docs/api/*.mdx` exists in `docs/api/openapi.json` (the 17 flagged were example ids or the `...` shorthand, checked by hand).
- Error envelope (`timestamp, status, message, data, error, path, errors`) matches `ApiResponse`.
- `docs/api/openapi.json` vs `ui/openapi.json` (the snapshot a test enforces): same 79 paths and 92 schemas. The docs copy differs in two ways: `deprecated: true` on 15 legacy `/api/workflow/...` routes (not set in code), and its own `info`, `servers` and `security`. Decision needed: set `@Deprecated`/deprecated in code, or keep the docs-only flags. Not changed.
- Pages 50 (glossary), 54–61 were checked by endpoint and envelope; line-by-line wording not re-read.

## ESLint warnings (gate)

- `eslint --fix` run twice (second pass needed for Tailwind class order). `tailwindcss/classnames-order`: 0 left (was 1434 in the first run).
- Prettier re-run on the changed TS files. 27 UI files changed in total, most by a few class-order lines; `ui/src/types/api.generated.ts` is the big one (Prettier wraps long lines, ~11k diff lines, no content change).
- Left: 19 `react-refresh/only-export-components` (13 files: non-component exports such as constants, hooks or context objects in component files) and 1 `react-hooks/incompatible-library` (`LogsPanel.tsx:200`). Each needs a move to a separate module plus import updates. Not done.

## Decisions applied (final pass)

- Legacy `/api/workflow/...` aliases are marked deprecated in code: `web/.../docs/ApiDocsCustomizer` (springdoc `OpenApiCustomizer`). Rule: every `/api/workflow/` path except `start`, `{sessionId}/history` and `{sessionId}/{workflowId}/stop`. Matches the 15 routes the docs flagged.
- The same customizer sets the API title, the build version, and the bearer and API-key security schemes.
- `ui/openapi.json` (the tested snapshot) is regenerated and `docs/api/openapi.json` is a copy of it. The copy no longer carries `servers`; the snapshot test strips them.
- `contributing/` pages: not reviewed and not published (not in nav), per user decision.
- alpha.3 is the version everywhere in the docs.

