# Notifications: one inbox for alerts and actions, on every client channel

Plan for a notification mechanism: everything Yukta wants to tell a person (an **info** alert) or needs from a person (an **action**, today an input request) becomes a stored `Notification` that reaches them through an in-app inbox, the desktop of their operating system, the CLI, MCP and outbound channels such as a webhook, and can be acted on from where it arrives.

Status (2026-11-15): **proposal**, nothing built. The "today" statements were read from the working tree on branch `chore/dogfood-leftovers` (based on `origin/main` at 72535301e); each names the class it comes from. Nothing was built or run. Items marked *to confirm* are checked in phase N0. Decided by Arun in two rounds of questions on 2026-11-15 (D1 to D8); D9 to D16 are proposed by this plan and open to change. The work is filed as epic #2348; see [Issues](#issues).

## Decisions

### Decided by Arun (2026-11-15)

| # | Question | Decision |
|---|---|---|
| D1 | How does a notification reach the desktop? | **The browser and the CLI.** An open Yukta tab raises OS notifications with the Web Notifications API; a new `yukta notify` command follows the stream and raises native notifications without a browser. No Web Push, no tray application. |
| D2 | Stored or derived? | **Stored.** A first-class `Notification` (kind `INFO` or `ACTION`) in a `NotificationStore`: in memory in Community, PostgreSQL in Plus. An `ACTION` points at its input request, whose state stays the single truth. |
| D3 | Where do `INFO` notifications come from? | **Engine events by rule** (run failed, run held by a supervisor, a delivery gave up, and so on) **and workflow authors** (a `NotificationGateway` for plugins, and the `NOTIFY` terminal gains an inbox target). No external "raise a notification" API. |
| D4 | Outbound channels | **One `NotificationChannel` SPI plus the webhook.** Slack apps, email and others come later as community plugins. |
| D5 | Whose inbox? | **Per named API key, with a shared fallback.** Recipients are selectors (`role:`, `key:`), limited to the sessions the key reaches. Read state and preferences are kept per key name. An unnamed key or an open server shares one inbox, `local`. |
| D6 | Acting from a notification | **Inline in the in-app inbox, a link from the OS.** `APPROVAL` and `CHOICE` are answered in place, `TEXT` and `STRUCTURED` in a side sheet. An OS notification only opens the item; the CLI notifier prints the answer command. |
| D7 | `InputResponder` and the delivery outbox | **Replaced in one go.** `NotificationChannel` replaces `InputResponder`; one outbox, one retry policy, one settings tree. Breaking for plugin-api and `yukta.input.responders.*`. |
| D8 | Place in the UI | **Bell, popover and an Inbox page**, with a short toast on arrival. The panel on the workflow details page stays as the view of that run. |

### Proposed by this plan

| # | Question | Proposal | Why |
|---|---|---|---|
| D9 | How does the producer hear about events? | **A reliable internal tap on `ExecutionEventFeed`**, not a normal subscription | The feed is `Sinks.many().multicast().directBestEffort()` and drops on overflow (`ExecutionEventFeed.java:29`). An approval that produces no notification is not acceptable |
| D10 | One notification per input request, or one per tier? | **One per request**, keyed `input:<requestId>`; an escalation changes its audience | The inbox shows one item per question; the approval chain (`asked`) already tells the story |
| D11 | A tier that targets supervisors and people at once (the built-in default route is `supervisor:*` + `role:OPERATOR`, `RouteResolver.java:47`) | **A grace period** (`yukta.notifications.action-grace`, default 2 s): the person is told only if the request is still open after it | Otherwise every request a supervisor answers in milliseconds flashes on a desktop |
| D12 | Catch-up after a reconnect | **A change sequence per store** and `Last-Event-ID` on the stream | The event feed is live only; a laptop that slept must not lose an approval |
| D13 | Notification rules | **A small own matcher** (category, sessions, workflows), not the supervisor rule engine | `EventRule` lives in `plugins/supervisors/rules`; core cannot depend on it |
| D14 | Native notifications in the CLI | **Run the OS tools** (`notify-send`, `osascript`, PowerShell); no Go dependency | Three short commands; a library adds cgo or D-Bus code for the same result |
| D15 | Toasts in the UI | **A small own toaster** (an `aria-live` region); no new package | `popover.tsx`, `sheet.tsx` and `tabs.tsx` exist; a toast is a list with a timer |
| D16 | Webhook payload | **Additive**: an `ACTION` keeps today's top-level fields and gains a `notification` object; an `INFO` carries only `notification` | Receivers written for alpha.4 keep working although the SPI changes |

## Principles

1. **One source of truth per fact.** Whether a question is still open is the input request's state. A notification never decides it; it resolves when the request ends.
2. **A notification is a record, a channel is a view.** The inbox, the bell, a desktop pop-up, the CLI and MCP all read the same stored record. Only outbound channels (webhook, later Slack or email) go through the outbox.
3. **Answers keep one path.** Acting from any channel ends in the existing `AnswerInputCommand` (REST answer endpoint, MCP `answer_input`, or the one-time token endpoint), so review, authorisation and audit do not change.
4. **Nothing reaches a person who may not see the run.** Every read and every stream is filtered by the caller's role, key name and sessions.
5. **Quiet by default.** Only actions and failed root runs notify out of the box; everything else is opt-in by rule or preference.

## What exists today

| Piece | State (class) |
|---|---|
| Input requests: broker, routes, tiers, votes, deadlines | merged (`InputBroker`, `RouteResolver`, `TierTargets`, `InputDeadlineSweeper`) |
| Five input events on the feed | `InputRequested`, `InputEscalated`, `InputAnswered`, `InputExpired`, `InputCancelled` (`ExecutionEvent`), published by `InputBroker` |
| Event stream | `GET /api/events/stream` (`EventsController`): live only, filters `executionId`, `tree`, `type`, `severity`. No replay. The controller does not read `CallerIdentity` (*to confirm* whether a filter scopes it by session elsewhere) |
| Severity | `EventSeverity` (`INFO`, `WARN`, `ERROR`) derived from an event |
| Delivery outbox | `InputDelivery` rows in `InputRequestStore` (`enqueueDelivery`, `dueDeliveries`, `saveDelivery`); `InputDeliveryWorker` (leader only, backoff 1 s to 5 min, 10 attempts) |
| Outbound SPI | `InputResponder` (plugin-api): `type()`, `deliver(InputRequest, Map)`; only for `external:<type>:<name>` targets of a tier (`InputBroker.queueDeliveries`) |
| Webhook | `WebhookInputResponder`: `yukta.input.responders.<name>` (`url`, `headers`, signing secret, `answerUrl`), HMAC `X-Yukta-Signature`, `EgressPolicy`, one-time token |
| Workflow-authored message | `NOTIFY` terminal: posts `{"text": ...}` to a webhook `url` per message |
| UI | `InputsPanel` and `InputRow` on the workflow details page; `InputsTile` on Control polls `getOpenInputs()` every 10 s; `ControlEventsProvider` holds an event stream for the Control page only. No bell, no inbox, no toast, no desktop notification |
| CLI | `yukta inputs list|show|answer|escalate`; `controlbus watch` follows the event stream (`client.StreamEvents`) |
| MCP | `list_pending_inputs`, `answer_input`, `escalate_input` |
| Identity | `ApiKeys.Match(name, role, sessions, agent)`; `ActorResolver` (open server: `anonymous` with ADMIN) |

What is missing: a person learns about a question only by looking at the right page. Info alerts (a failed run, a supervisor that paused a run, a delivery that gave up) exist only as live events. Nothing is kept per person, nothing reaches the desktop, and reaching out is possible only to a tier that is allowed to answer.

## Architecture

```
 sources                                core                                   clients
 ───────                                ────                                   ───────
 InputBroker ─ Input* events ─┐
 TrackerEventApplier ─ status ┼─▶ ExecutionEventFeed ──reliable tap──▶ NotificationProducer
 DirectiveDispatcher ─ outcome┘        (lossy for SSE)                  │  rules, grace, dedupe
                                                                         ▼
 plugin ─ NotificationGateway.notify(spec) ───────────────────▶ NotificationService
 NOTIFY terminal (target: inbox) ─────────────────────────────▶   create / update / resolve
                                                                   receipts (read, dismissed)
                                                                   change sequence
                                                                         │
                                                  ┌──────────────────────┼─────────────────────┐
                                                  ▼                      ▼                     ▼
                                          NotificationStore       change stream          outbox rows
                                          (memory | Postgres)          │                      │
                                                                       │           NotificationDeliveryWorker
                              REST /api/notifications ◀────────────────┤           (leader, backoff, token)
                              SSE  /api/notifications/stream ◀─────────┘                      │
                                   │                                              NotificationChannel SPI
              ┌────────────────────┼───────────────────┬──────────────┐              ├─ webhook (core)
              ▼                    ▼                   ▼              ▼              └─ community plugins
        UI bell, popover,    browser OS          `yukta notify`    MCP
        Inbox page, toast    notification        native OS         list_notifications
              │                    │ click             │ prints command
              └──── answer ────────┴───────────────────┴──▶ POST /api/executions/{e}/inputs/{r}/answer (unchanged)
```

Module placement follows the existing layering (ArchUnit rules in `architecture`):

| Module | New |
|---|---|
| `plugin-api` | package `com.infenia.yukta.plugin.notify`: `Notification`, `NotificationSpec`, `NotificationKind`, `NotificationSeverity`, `NotificationChannel`, `ChannelContext`, `NotificationGateway`. `InputResponder` is removed (D7) |
| `core` | package `com.infenia.yukta.service.notify`: `NotificationService`, `NotificationStore`, `InMemoryNotificationStore`, `NotificationProducer`, `NotificationRules`, `Recipients`, `NotificationDeliveryWorker`, `WebhookChannel`, `DefaultNotificationGateway`, `NotificationRetention`; `YuktaProperties.Notifications` |
| `web` | `NotificationsController`, `NotificationView`, `NotificationSummary`, `NotificationPreferencesView` |
| `mcp` | tools `list_notifications`, `mark_notifications_read` |
| `plus/persistence-postgres` | `R2dbcNotificationStore`, a migration, `NotificationStoreContract` in `plus/src/testFixtures` |
| `plugins/terminals/notify-terminal` | `target: inbox` |
| `ui` | `src/features/inbox/` |
| `cli` | `internal/commands/notifications`, `internal/client/notifications.go`, `internal/notify` (native notifications) |

## Contracts

### The record (plugin-api)

```java
public record Notification(
    String id,
    String key,                       // dedupe key: "input:<requestId>", "run.failed:<executionId>", or the author's
    NotificationKind kind,            // INFO | ACTION
    NotificationSeverity severity,    // INFO | WARN | ERROR (same names as EventSeverity)
    String category,                  // "input", "run.failed", "run.finished", "run.held", "delivery.failed", "workflow"
    String title,                     // one line, redacted, at most 200 characters
    @Nullable String body,            // plain text, redacted, at most 4 KB
    @Nullable String sessionId,
    @Nullable String workflowId,
    @Nullable String executionId,
    @Nullable String nodeId,
    @Nullable String inputRequestId,  // set for ACTION
    List<String> audience,            // role:VIEWER|OPERATOR|ADMIN, key:<name>
    State state,                      // ACTIVE | RESOLVED
    @Nullable String resolution,      // "answered by key:arun: approve", "expired", "cancelled: run-ended"
    int count,                        // how many times the same key fired inside its cooldown
    long seq,                         // the store's change sequence at the last change
    Instant createdAt,
    Instant updatedAt) {}
```

- `ACTION` is `ACTIVE` while its request is `OPEN` or `ESCALATED` for a tier that targets people, and `RESOLVED` afterwards. An `INFO` is `ACTIVE` until retention removes it (it has nothing to resolve); "done" for an `INFO` is per person (dismissed).
- Per-person state is a separate sparse row: `Receipt(notificationId, recipient, readAt, dismissedAt)`. No row means unread.
- `audience` uses the selector grammar of `Audience` for people (`role:`, `key:`) and adds `role:VIEWER` for `INFO`. Supervisors are never an audience (they have the event stream), and outbound channels are not an audience either: they are deliveries.
- `recipient` is the key name; a caller without a name is `local` (*to confirm* in N0 how a single unnamed `yukta.api-key` resolves in `ActorResolver.fromKey`).

### Who sees a notification (`Recipients`)

A caller sees a notification when all hold:

1. the notification has no `sessionId`, or `Match.reaches(sessionId)` is true;
2. a selector matches: `key:<name>` equals the caller's key name, or `role:X` is at or below the caller's role (`role:OPERATOR` matches operators and admins, as in `Audience`);
3. for an `ACTION`, the caller is a person (`Match.agent()` false) unless the selector names the key itself.

The same check filters the list, the summary, the stream and the MCP tool. The answer itself is still authorised by `DefaultAnswerAuthorizer`; seeing an item never grants the right to answer it.

### Sources and categories

| Category | Kind | Severity | Raised when | Default |
|---|---|---|---|---|
| `input` | ACTION | from risk: LOW `INFO`, MEDIUM `WARN`, HIGH `ERROR` | a request reaches a tier with a person selector (after the grace of D11); audience follows the tier on escalation; resolves on answer, expiry or cancellation | always on |
| `run.failed` | INFO | ERROR | a root run ends `FAILURE`, `ERROR`, `TIMED_OUT` or `ABORTED` (`WorkflowStatusChanged`) | on, `role:OPERATOR` |
| `run.finished` | INFO | INFO | a root run ends successfully | off |
| `run.held` | INFO | WARN | a `ControlOutcome` applied by a supervisor pauses or stops a run | off |
| `delivery.failed` | INFO | WARN | the outbox gives up on a delivery | on, `role:ADMIN` |
| `workflow` | INFO | chosen by the author | `NotificationGateway.notify` or the `NOTIFY` terminal with `target: inbox` | always on (the workflow asked for it) |

Runs of a child workflow and of the internal session never raise `run.*` (the parent run reports). Each rule may override audience, severity and channels, and narrow by session or workflow:

```yaml
yukta:
  notifications:
    enabled: true
    retention: 7d             # RESOLVED actions and INFO older than this are removed
    max-items: 5000           # in-memory store bound, oldest RESOLVED and INFO first
    action-grace: 2s          # D11
    rules:
      run-failed:
        category: run.failed
        sessions: ["release-*"]          # glob; empty means all
        audience: ["role:OPERATOR"]
        channels: ["webhook:ops"]         # besides the inbox, which is always written
        cooldown: 5m                      # same key inside the cooldown raises count, not a new item
      nightly-done:
        category: run.finished
        workflows: ["nightly"]
        audience: ["key:arun"]
    channels:
      webhook:
        ops:
          url: https://hooks.example.com/yukta
          headers: { Authorization: "Bearer ${OPS_TOKEN}" }
          signing-secret: ${OPS_SECRET}
          answer-url: https://yukta.example.com/api/inputs/token-answer
```

A rule is validated at start-up (unknown category, bad selector, a channel that is not configured), and the start fails with the rule's name.

### Preferences (per recipient)

`NotificationPreferences(recipient, Map<category, Level>, Set<mutedSessions>)` with `Level` one of `OFF`, `INBOX`, `DESKTOP` (inbox plus a desktop notification). `input` cannot be `OFF`. They are kept on the server so the browser and `yukta notify` agree; the browser's permission and the "show details on the desktop" switch are per device and stay in `localStorage`.

### The store

```java
public interface NotificationStore {
  Mono<Notification> upsert(Notification n);                     // by key; assigns seq
  Mono<Notification> resolve(String key, String resolution);     // no-op when already RESOLVED
  Flux<Notification> find(Query q);                              // state, kind, severity, session, since seq, page
  Mono<Notification> findById(String id);
  Mono<Void> saveReceipt(Receipt r);
  Flux<Receipt> receipts(String recipient, Collection<String> ids);
  Mono<Preferences> preferences(String recipient);
  Mono<Void> savePreferences(Preferences p);
  Mono<Delivery> enqueueDelivery(Delivery d);                    // moved from InputRequestStore
  Flux<Delivery> dueDeliveries(Instant now);
  Mono<Void> saveDelivery(Delivery d);
  Mono<Long> purge(Instant resolvedBefore);
  String epoch();                                                // changes when seq restarts (in-memory restart)
}
```

`seq` is a counter that every create, update and resolve advances (an `AtomicLong` in memory, a sequence in Postgres). The stream's event id is `<epoch>-<seq>`; a client that reconnects with an id of another epoch gets a `reset` event and loads the list again.

### The channel SPI (replaces `InputResponder`)

```java
public interface NotificationChannel {
  String type();                                                 // "webhook"
  Mono<Void> deliver(Notification notification, ChannelContext context);
}

public record ChannelContext(
    String name,                         // the configured target, "ops"
    Map<String, Object> settings,        // channel settings merged with the tier's settings
    @Nullable InputRequest request,      // the redacted request of an ACTION
    @Nullable String answerToken,        // one-time token, only when a tier names this channel as a responder
    @Nullable Instant answerExpires) {}
```

Delivery stays at least once and idempotent by notification id. Two things put a row in the outbox:

- a route tier that targets `external:<type>:<name>` (the selector syntax stays, so `Audience`, stored routes and `Responder.Type.EXTERNAL` do not change): an `ACTION` delivery **with** an answer token, as today;
- a rule that lists `channels`: a delivery **without** a token (the channel informs; people answer in Yukta).

`NotificationDeliveryWorker` is today's `InputDeliveryWorker` with the same leader check, backoff and attempt limit; before sending an `ACTION` it still skips a request that ended or left the tier. Giving up raises `delivery.failed` (never for a failed `delivery.failed` delivery).

Removed with a clear start-up error naming the replacement: `yukta.input.responders.*` becomes `yukta.notifications.channels.webhook.*`. `PluginApi`'s SPI version goes up (#2329 added the check).

### Workflow authors (plugin-api)

```java
public interface NotificationGateway {
  Mono<Void> notify(NotificationSpec spec);   // identity from the Reactor context, like InputGateway (ADR 0001 D9)
}
public record NotificationSpec(
    String title, @Nullable String body, NotificationSeverity severity,
    List<String> audience, @Nullable String key, List<String> channels) {}
```

Plugins take it through `ObjectProvider<NotificationGateway>`. Limits: 20 notifications per execution per minute and 200 per execution (a `NotificationLimitException` beyond that, like `InputLimitException`); `channels` may only name configured channels. The `NOTIFY` terminal gets `target` (`webhook`, the default and today's behaviour, or `inbox`) with `title`, `severity` and `audience`; `url` is required only for `webhook`.

### REST

| Endpoint | Purpose |
|---|---|
| `GET /api/notifications?state=&kind=&severity=&unread=&sessionId=&page=&size=` | the caller's items, newest first; each has `read`, `dismissed` and, for an `ACTION`, the embedded `InputRequestView` (so the inbox answers without a second call) |
| `GET /api/notifications/summary` | `{ actions, unread, highestSeverity }` for the bell |
| `POST /api/notifications/read` | body `{ ids: [...] }` or `{ all: true, upToSeq: n }` |
| `POST /api/notifications/{id}/dismiss` | hides an `INFO` or a resolved `ACTION` for the caller; an active `ACTION` cannot be dismissed |
| `GET /api/notifications/stream` | SSE for the caller: `created`, `updated`, `resolved`, `receipt`, `reset`; honours `Last-Event-ID`; heartbeat from `SseStreams` |
| `GET` / `PUT /api/notifications/preferences` | the caller's preferences |

Answering and escalating stay on `/api/executions/{executionId}/inputs/{requestId}/answer|escalate`. On a standby server the endpoints behave like the input endpoints (ADR 0001 D12, `NotLeaderException`, 503). Role needed: `VIEWER` to read one's inbox and set preferences.

### MCP and CLI

- MCP: `list_notifications` (same filters, the caller's items) and `mark_notifications_read`. `list_pending_inputs`, `answer_input` and `escalate_input` stay as they are. Both tools return wrapper records (the Spring AI 2.0 gotcha: no `Flux` results, no `Mono<List<T>>` with a generated schema).
- CLI: `yukta notifications list|read|dismiss` and `yukta notify`:

```
yukta notify [--min-severity WARN] [--kind ACTION] [--session ID] [--print] [--exec 'cmd {{.Title}}']
```

`notify` follows `/api/notifications/stream` (reconnect with the last event id through the existing stream client), honours the server-side preferences, and raises a native notification per new item: `notify-send` on Linux (with `--action` and `xdg-open` for the link where the installed libnotify supports it), `osascript -e 'display notification'` on macOS, a PowerShell toast on Windows. With no notifier found it falls back to `--print`. For an `ACTION` the text ends with the ready command, `yukta inputs answer <execution> <request> --decision ...`. `--exec` runs any command instead, which covers tmux, a status bar or a phone push service without code in Yukta. Running it at login is a documented systemd user unit and launchd snippet, not an installer.

## Flows

**A question (action).**
1. A node asks; `InputBroker` stores the request and publishes `InputRequested`.
2. The tap hands the event to `NotificationProducer`. The tier targets `supervisor:*` and `role:OPERATOR`, so it waits `action-grace`. A supervisor answers in time: nothing is raised.
3. Otherwise the producer upserts `input:<requestId>` (kind `ACTION`, audience `role:OPERATOR`, title from the prompt's first line). The store assigns a `seq`; the stream sends `created` to every connected caller that `Recipients` lets through.
4. The UI raises the bell count and a toast; if the tab is hidden and the person's level is `DESKTOP`, a browser notification. `yukta notify` raises a native one.
5. The person answers inline. The UI posts to the unchanged answer endpoint; the broker publishes `InputAnswered`; the producer resolves the notification (`answered by key:arun: approve`); the stream sends `resolved`; every other client drops the item from "Needs action".
6. An escalation instead replaces the audience with the next tier's person selectors and adds the escalation reason to the body; the earlier recipients see it leave their list, the new ones get `created`.

**An alert (info).** A root run fails; the tap delivers `WorkflowStatusChanged`; rule `run-failed` matches; the producer upserts `run.failed:<executionId>` and enqueues a delivery to `webhook:ops`. The inbox shows it under "All" with a link to the run; reading or dismissing it is per person.

**A laptop that slept.** The browser reconnects with `Last-Event-ID: <epoch>-4812`; the server replays every change with a higher `seq` that the caller may see (bounded to 500, then `reset`). No approval is missed, and resolved ones are not shown as new.

**Start-up reconcile (leader).** For every open request whose tier has a person selector and no active notification, raise one; for every open request with `external:` targets and no delivery row, enqueue one. This covers the upgrade (requests opened before this feature) and replaces a data migration of pending `input_deliveries` rows.

## Security

| Concern | Measure |
|---|---|
| Seeing another session's question | `Recipients` on list, summary, stream and MCP; tested per endpoint with a session-limited key. N0 confirms whether `/api/events/stream` has the same gap and files it separately if so |
| Secrets in text | title and body pass `InputRedactor`; author text is length-limited and rendered as plain text (no HTML, no Markdown links) |
| Desktop pop-ups on a shared or locked screen | a desktop notification shows the title and the workflow, never `context`; a per-device switch reduces it to "Yukta needs your input" |
| Answering by accident | no answer buttons on OS notifications (D6); `HIGH` risk keeps the confirm step in the inbox; `expectedVersion` is sent as today |
| Floods | per-execution limits on the gateway, `cooldown` and `count` per key, a cap on stored items, a cap of stream connections per key (8) |
| Outbound calls | the webhook channel keeps `EgressPolicy`, the HMAC signature and the one-time token rules of #1796; a rule's channel never gets a token |
| New answer path | none: all channels end in `AnswerInputCommand` |
| Audit | raising and resolving an `ACTION` is already in the execution log (`INPUT` events); deliveries keep `INPUT_DELIVERED` and `INPUT_DELIVERY_FAILED` for actions and add `NOTIFICATION_DELIVERED` and `NOTIFICATION_DELIVERY_FAILED` under the existing `INPUT` log type's sibling (*to confirm*: reuse `INPUT` with a new event name, or add a `NOTIFICATION` type; ADR 0001 D11 chose one type with an `event` field) |

## UI and UX design

Artboards come first, drawn on the running app's shell (header with the identity chip, theme toggle and account menu), a first batch of three (bell with popover, Inbox page, toast) for review before the rest. The design system artifact is the source for tokens.

| Surface | Behaviour |
|---|---|
| **Bell** in `AppHeader`, left of `IdentityChip` | Count of active actions for me; a dot when only unread info exists; colour from the highest severity. `aria-label` "Notifications, 2 need action". Opens the popover; a command palette entry "Open inbox" |
| **Popover** (`popover.tsx`) | "Needs action" (up to 5, oldest deadline first) with inline Approve / Reject or the options of a `CHOICE`; "Recent" (5 newest info); "Mark all read"; "Open inbox" |
| **Inbox page** `/inbox` | Tabs "Needs action", "All", "Resolved"; filters severity, session, category; rows show title, workflow, risk, tier, time left (`remaining` from `InputRow`), the approval chain (`ApprovalChain`); `?item=<id>` opens one item (the target of every OS notification and CLI link). `TEXT` and `STRUCTURED` open `sheet.tsx` with the form `InputRow` already renders |
| **Toast** | New `ACTION` or `ERROR` info while the tab is visible: title, "View", and for `APPROVAL` the two buttons. Stays until dismissed for actions, 8 s for info; an `aria-live="polite"` region (`assertive` for `ERROR`) |
| **Desktop** | Asked for permission only from the preferences switch (a click, never on load). `new Notification(title, { body, tag: id })` when `document.visibilityState` is not `visible`; `tag` stops duplicates across tabs; a click focuses the tab and goes to `/inbox?item=<id>`. The document title shows `(2) Yukta` |
| **Preferences** (in the Inbox page header) | Per category: off, inbox, desktop; muted sessions; the per-device desktop switches |
| **Workflow details** | `InputsPanel` stays; it is refreshed by the notification stream instead of waiting for its own poll |
| **Control page** | `InputsTile` reads the summary and links to `/inbox` (its 10 s poll goes) |

Code: `ui/src/features/inbox/` with `api.ts`, `NotificationsProvider.tsx` (one stream for the whole app, mounted in `App.tsx`, built on `useEventSourceJson` and `KeyedEventSource`, which already sends `Last-Event-ID`), `NotificationBell.tsx`, `InboxPopover.tsx`, `InboxPage.tsx`, `NotificationItem.tsx`, `Toaster.tsx`, `useDesktopNotifications.ts`, `PreferencesSheet.tsx`. The answering part of `InputRow` moves to a shared `InputAnswerForm` used by the row, the popover and the sheet. States designed for each surface: empty, loading, stream disconnected (the bell shows a muted state and the list falls back to a 30 s poll), permission denied, answered elsewhere while open (the item resolves in place with who answered).

## Editions, durability and HA

- Community: `InMemoryNotificationStore`, bounded by `max-items`, lost on restart like the in-memory request store; the reconcile rebuilds active actions for requests that survive (none in Community, all in Plus).
- Plus: `R2dbcNotificationStore`; one migration (the next free version after the open #2342) creating `notifications`, `notification_receipts`, `notification_preferences`, `notification_deliveries` and dropping `input_deliveries`. `NotificationStoreContract` runs for both stores from `plus` (ADR 0001 D15). The store follows the input store's setting (the one `PostgresPersistenceAutoConfigurationTest` covers as the input store type; *to confirm* its exact name in N0), so no new `FeatureGateEnvironmentPostProcessor` row is needed.
- HA: the producer, the grace timers, retention and the delivery worker run only on the leader (`LeaderElection`, `LeaderTimer`). A failover aborts the old leader's runs; their requests are cancelled and so their notifications resolve as `cancelled: run-ended`.
- Native image: `@RegisterReflectionForBinding` for the new views and stream envelope; new beans in `boot/src/test/resources/expected-beans.txt`; OpenAPI snapshot and `ui/src/types/api.generated.ts` regenerated.
- Metrics: `yukta.notifications.raised{category,kind}`, `yukta.notifications.active{kind}`, `yukta.notifications.stream.subscribers`, `yukta.notifications.tap.dropped`; the delivery counter keeps its name pattern as `yukta.notifications.delivery{channel,outcome}`.

## Phases

One PR per phase, each with `yk check --full --no-heal` green. N4 is the breaking one and ships in one release with N5.

| Phase | Content | Depends on |
|---|---|---|
| **N0** Decisions and checks | ADR `design/decisions/0003-notifications.md` (D1 to D16); confirm the *to confirm* items (unnamed key identity, event stream scoping, log type, whether the tap must also cover `ReplayEvents`); file the issues; artboard batch 1 and its review | |
| **N1** Model and store | plugin-api records; `NotificationStore`, `InMemoryNotificationStore`, `NotificationService` (upsert by key, resolve, receipts, seq and epoch, cooldown and count, retention), `Recipients`, `YuktaProperties.Notifications` with validation. No behaviour change | N0 |
| **N2** Producer | the reliable tap in `ExecutionEventFeed` (a bounded unicast queue for the non-log, non-node events, with the drop metric); `NotificationProducer`: input lifecycle with grace and escalation, `run.*`, `run.held`; `NotificationRules`; start-up reconcile | N1 |
| **N3** REST and stream | `NotificationsController` (list, summary, read, dismiss, preferences, SSE with `Last-Event-ID` and `reset`), views, recipient filtering, OpenAPI and UI types | N2 |
| **N4** Channel SPI and outbox (**breaking**) | `NotificationChannel` and `ChannelContext`; outbox methods move to `NotificationStore`; `NotificationDeliveryWorker`; `WebhookChannel` with the additive payload; rule `channels`; `delivery.failed`; remove `InputResponder`, `InputDelivery`, `InputDeliveryWorker`, `WebhookInputResponder`, `yukta.input.responders.*` with a start-up error; SPI version; `examples/input-pull`, migration notes | N1 |
| **N5** Plus store | `R2dbcNotificationStore`, the migration, the contract test | N4 |
| **N6a** UI: provider, bell, popover, toast | `NotificationsProvider`, shared `InputAnswerForm`, bell, popover, toaster, title count; Vitest and Playwright (MSW) | N3, artboards approved |
| **N6b** UI: Inbox page | page, filters, sheet, `?item=`, palette entry, `InputsTile` and `InputsPanel` wiring | N6a |
| **N6c** UI: desktop and preferences | `useDesktopNotifications`, preferences sheet | N6b |
| **N7** CLI and MCP | `yukta notifications`, `yukta notify` with the three native notifiers and `--exec`; the two MCP tools; `docs/reference/client-parity.mdx` | N3 |
| **N8** Workflow authors | `DefaultNotificationGateway` with limits; `NOTIFY` `target: inbox`; an example workflow | N1 |
| **N9** Docs and hardening | a concept page and an operate guide, configuration and metrics reference, the extend guide for channels, what's new; a k6 run of 1 000 open requests and 200 stream clients; release notes | all |

N6, N7 and N8 are independent of each other once N3 is in; N4 and N5 can run next to the UI work.

## Test strategy

- **Store contract** (`NotificationStoreContract`): upsert by key is idempotent, `seq` strictly increases, resolve is terminal, receipts are per recipient, `find` since a `seq`, purge keeps active actions, outbox ordering. Run for both stores.
- **Producer**: `StepVerifier` with virtual time for the grace period (answered inside: nothing; after: one item), escalation changes the audience, answer, expiry and cancellation resolve, child and internal runs raise nothing, cooldown raises `count`, a burst of 10 000 log events does not lose an `InputRequested` (the tap).
- **Recipients**: a table test of role, key, session reach and agent keys.
- **Web**: `WebTestClient` per endpoint with an admin, a session-limited operator and a viewer; the stream's replay and `reset`; 503 on a standby.
- **Delivery**: the existing worker tests move with the class; a token only for tier deliveries; `delivery.failed` raised once.
- **UI**: Vitest for the provider (stream events to state, reconnect), bell counts, inline answer, answered elsewhere; Playwright with MSW and a mock event source for the popover, the page, the sheet and the toast; desktop notifications with a stubbed `Notification`.
- **CLI**: `go test` with a fake stream and a fake notifier command per OS; the fallback to `--print`.
- **Architecture**: `yk gradle :architecture:test :plus:plus-architecture:test` (core must not reach `web` or a plugin).

## Risks

| Risk | Answer |
|---|---|
| The tap grows without bound under an event storm | it carries only input, workflow status and control outcome events; bounded (10 000) with a metric and an `ERROR` log; the reconcile also runs on a timer (5 min) and repairs a missed action |
| Notification noise makes people ignore the bell | quiet defaults, grace, cooldown, per-person levels, only root runs |
| Breaking the alpha.4 webhook users | additive payload (D16), a start-up error that names the new property, a migration page |
| Browser notifications need permission and an open tab | stated in the docs; `yukta notify` covers the closed-browser case; Web Push stays possible later on the same stream |
| Two tabs and a CLI notifier raise the same item three times | browser `tag` dedupes tabs; the docs say to pick the browser or `yukta notify` for the desktop level |
| In-memory `seq` restarts | the epoch in the event id and the `reset` event |

## Not in this plan

Web Push and a service worker; a tray application; email and Slack channels (the SPI allows them as plugins); an external API to raise notifications; user accounts; answer buttons on OS notifications; snooze, digests and quiet hours; personal outbound channels per user; translations; sounds. Each can be added on the stored record and the stream without changing them.

## Questions left

| # | Question | Proposal |
|---|---|---|
| Q1 | Should `run.failed` be on by default? With dogfood running hundreds of runs a day it may be loud | on, but only for root runs outside the internal session; dogfood sets a rule with `sessions` |
| Q2 | May a `VIEWER` see an `ACTION` they cannot answer? | yes, read-only, when a rule or tier names `role:VIEWER`; never by default |
| Q3 | Should a resolved action stay in "All" for the people who did not answer it? | yes, under "Resolved", with who answered, until retention |
| Q4 | Execution log: reuse the `INPUT` type for notification deliveries or add `NOTIFICATION`? | add an event name under a new `NOTIFICATION` type only if an `INFO` delivery must be audited per run; otherwise metrics are enough |

## Issues

Epic #2348. The artboard review is part of N0 and blocks N6a.

| Phase | Issue |
|---|---|
| N0 | #2349 |
| N1 | #2350 |
| N2 | #2351 |
| N3 | #2352 |
| N4 | #2353 (breaking) |
| N5 | #2354 (breaking, ships with N4) |
| N6a | #2355 |
| N6b | #2356 |
| N6c | #2357 |
| N7 | #2358 |
| N8 | #2359 |
| N9 | #2360 |
