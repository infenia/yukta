# ADR 0003: Notifications and inbox

- **Status:** accepted on 2026-11-15. D1 to D8 were decided by Arun on 2026-11-15; D9 to D16, the findings below and Q1 to Q4 were put to Arun in two rounds on the same day. Every proposal was accepted as written, except that Arun's answers on recipient identity and scoped keys (D17, D18) are new.
- **Date:** 2026-11-15
- **Prepared by:** Claude Code, at the request of Arun, from a read of `origin/main` at f84efb5cd (nothing was built or run). **Decided by:** Arun, 2026-11-15.
- **Plan:** `design/plans/notifications.md`. Epic #2348, this phase #2349. Builds on [ADR 0001](0001-input-requests.md).

## Decisions

### Decided by Arun before the plan (D1 to D8)

| # | Decision | Outcome |
|---|---|---|
| D1 | Desktop reach | The browser (Web Notifications API) and a new `yukta notify` CLI command. No Web Push, no tray application |
| D2 | Stored or derived | Stored: a first-class `Notification` (`INFO` or `ACTION`) in a `NotificationStore`, in memory in Community and PostgreSQL in Plus. An `ACTION` points at its input request, whose state stays the single truth |
| D3 | Sources of `INFO` | Engine events by rule, and workflow authors (`NotificationGateway`, `NOTIFY` terminal with an inbox target). No external "raise a notification" API |
| D4 | Outbound channels | One `NotificationChannel` SPI plus the webhook; Slack, email and others later as community plugins |
| D5 | Whose inbox | Per named API key with a shared fallback; recipients are `role:` and `key:` selectors limited to the sessions the key reaches |
| D6 | Acting from a notification | Inline in the in-app inbox; a link from the OS; the CLI prints the answer command |
| D7 | `InputResponder` and the outbox | Replaced in one go by `NotificationChannel`; breaking for plugin-api and `yukta.input.responders.*` |
| D8 | UI place | Bell, popover, Inbox page, short toast; the panel on the workflow details page stays |

### Proposed by the plan (D9 to D16), all accepted

| # | Decision | Outcome |
|---|---|---|
| D9 | How the producer hears about events | A reliable internal tap on `ExecutionEventFeed` (bounded queue of 10 000, drop metric, error log), not a normal subscription; the 5-minute reconcile repairs a miss |
| D10 | One notification per request or per tier | One per request, key `input:<requestId>`; an escalation changes the audience |
| D11 | Tier with supervisors and people | A grace period `yukta.notifications.action-grace` (default 2 s): the person is told only if the request is still open after it |
| D12 | Catch-up after a reconnect | A change sequence per store; stream id `<epoch>-<seq>`; `Last-Event-ID` replay (bounded to 500) then a `reset` event |
| D13 | Notification rules | A small own matcher (category, sessions, workflows), not the supervisor `EventRule` |
| D14 | Native notifications in the CLI | Run the OS tools (`notify-send`, `osascript`, PowerShell); no Go dependency |
| D15 | Toasts in the UI | A small own toaster (an `aria-live` region); no new package |
| D16 | Webhook payload | Additive: an `ACTION` keeps today's top-level fields and gains a `notification` object; an `INFO` carries only `notification` |

### Added by the code reading and the answers (D17, D18)

| # | Decision | Outcome | Why |
|---|---|---|---|
| D17 | Recipient of a caller without a name | The open server (`anonymous`) and a key entry without a name (`unnamed`) both map to the shared inbox `local`. A key named `local` is rejected at start-up | F1. Arun, 2026-11-15 (recommended option) |
| D18 | Session-limited keys | They may call `/api/notifications/**` and its stream; `Recipients` shows them only notifications of their sessions and those without a session. N3 adds the paths to `ApiKeyFilter` next to `/api/me` | F2. Arun, 2026-11-15 (recommended option) |

### Questions of the plan (Q1 to Q4)

| # | Outcome |
|---|---|
| Q1 | `run.failed` is on by default, for root runs outside the internal session, audience `role:OPERATOR`. Dogfood narrows it with a rule that sets `sessions` |
| Q2 | A `VIEWER` sees an `ACTION` they cannot answer only when a rule or tier names `role:VIEWER`, read-only; never by default |
| Q3 | A resolved action stays under "Resolved" for the people who did not answer it, with who answered, until retention |
| Q4 | Reuse the `INPUT` log type with new event names (see F4); no `NOTIFICATION` type. Deliveries of an `INFO` notification are counted in metrics only |

## Findings (the *to confirm* items of the plan)

"Confirmed" means read in code; "inferred" means concluded from code that was read but not run. Nothing was run in this phase.

### F1. Identity of a caller without a name (confirmed)
- `ApiKeys.read` gives a key entry without `name` the name `unnamed` (`ApiKeys.java`, `Objects.requireNonNullElse(entry.name(), "unnamed")`). The single property `yukta.api-key` no longer exists: only `yukta.api-keys[]` is read, so ADR 0001 F3(a) ("a single unnamed `yukta.api-key` gives actor `null`") is out of date.
- With a `Match`, `ActorResolver.fromKey` returns `ActorInfo.human(match.name(), ...)`, so the recipient would be `unnamed`. With no `Match` and an open server it returns `ActorInfo.human("anonymous", ADMIN, Set.of())`, so the recipient would be `anonymous`. Neither is `local`, which the plan assumed.
- Consequence: D17. `Recipients` maps both to `local`; start-up validation rejects a key named `local` (a new check in N1, next to the rule validation).

### F2. Scoping of `GET /api/events/stream` (confirmed by reading; not a leak)
- `EventsController` does not read `CallerIdentity`: it filters only by `executionId`, `tree`, `type` and `severity`.
- The scoping is done before it, in `ApiKeyFilter.permitted`: a key with sessions passes only `/api/me`, `/api/sessions|workflow/{session}/**` (checked with `Match.reaches`) and `/api/executions/{id}/**` (checked against the run's session); every other path, `/api/events/stream` included, gets 403. So a session-limited key cannot read other sessions' events through the stream, and **no security issue is filed**.
- The same rule locks a session-limited key out of every new `/api/notifications/**` path unless the filter is changed. D18 settles it. MCP uses `McpToolAccess.reaches` for the same purpose (inferred for the new tools; N7 tests it).

### F3. Reliable tap and `ReplayEvents` (confirmed)
- `ExecutionEventFeed` is `Sinks.many().multicast().directBestEffort()` with a 10 000 buffer per subscriber that drops the latest on overflow (`yukta.tracker.dropped{kind=feed}`), so a normal subscription can lose an `InputRequested` (D9 stands).
- `ReplayEvents.from` rebuilds status events from the execution log for `SupervisorService.replay` (a draft supervisor tried on a past run). It never publishes to the feed. The tap must **not** see these events: replaying a run must not raise notifications.

### F4. Execution log type (confirmed)
- `ExecutionLogEvent.Type.INPUT` exists, with an `event` field (`INPUT_REQUESTED` ... `INPUT_CANCELLED`), and `InputDeliveryWorker` already writes `INPUT_DELIVERED` and `INPUT_DELIVERY_FAILED` (`InputDeliveryWorker.java:239,256`). So ADR 0001 D11 holds and Q4 is settled: no new type, no new case in `ExecutionRecovery`, `LogRetention`, `RunAnalytics` or `StartIdempotency`.
- N4 keeps those two event names for deliveries of an `ACTION` that has an input request; the worker moves to `NotificationDeliveryWorker` with the same names.

### F5. Postgres store setting and Flyway version (confirmed)
- The input store follows `yukta.input.store-type=postgres`: the gate row in `FeatureGateEnvironmentPostProcessor` (`Gate("yukta.input.store-type", ...)`) and `@ConditionalOnProperty` on `PostgresPersistenceAutoConfiguration.inputRequestStore`. The notification store follows the same setting, so no new gate row is needed (as the plan assumed).
- Flyway: the alpha migrations were squashed into `V1__yukta_core.sql` (#2282); that is the only file in `plus/persistence-postgres/src/main/resources/db/migration`. The notification migration in N5 is therefore **V2**. The plan's "next free version after the open #2342" is stale.

## Consequences for the sub-issues
- #2350 (N1): `Recipients` maps `anonymous` and `unnamed` to `local`; start-up rejects a key named `local`.
- #2352 (N3): add `/api/notifications/**` to the paths a session-limited key may call, with a test per endpoint; the plan's "to confirm" about the events stream is closed (F2).
- #2354 (N5): the migration is V2, not "the next free version after #2342".
- #2353 (N4): keep `INPUT_DELIVERED` and `INPUT_DELIVERY_FAILED` as the log event names (F4).

## Open points
- Artboard batch 1 (bell with popover, Inbox page, toast) is drawn and reviewed after this ADR; it blocks N6a only.
