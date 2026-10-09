# messaging — Review, Improvements & Optimisations

> Scope: `messaging/src/main` (11 files, ≈1.03k lines) reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> Companion: [`messaging-module-guide.html`](messaging-module-guide.html) is the interactive guide.

## 1. Verdict

`messaging` defines the `Message<T>` envelope that flows on every edge. It also defines a
`NodeMessageChannel` hook around each plugin, and an optional `MessageStore` wire-tap. The envelope
is immutable and follows Enterprise Integration Patterns naming (correlation, sequence, dead-letter
and retry headers), which is the right base.

The problems are mostly weight and duplication:

- **`DefaultMessage` is 431 lines.** About 200 of those are hand-written copy constructors for
  `withX` methods that Lombok `@With` (already used on half the fields) would generate.
  `Message` exposes every field twice: `getX()` from the interface and `x()` from the record.
- **Message history is stamped twice per node** for every internal-core router or filter plugin.
  The plugin calls `withAddedHistory(nodeId)`, then core's `StreamTopologyDecorator` calls it again.
  Each stamp copies the whole list, so history work grows quadratically with path length.
- **Null metadata values crash.** `Map.copyOf` rejects them, as does `ConcurrentHashMap` in
  `withHeader`.
- **The persistence path is dead code.** There is no `MessageStore` implementation anywhere, so
  `PersistingNodeMessageChannel*` never activates. Its `@ConditionalOnBean` on a component-scanned
  `@Configuration` would also be unreliable.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Envelope design | 3.5/5 | Immutable, EIP-complete. Two accessor styles per field |
| Correctness | 3/5 | Duplicate history, null-metadata NPE |
| Performance | 3/5 | O(n) list copy per hop. Full 22-field rebuild per `with*` |
| Simplicity | 2/5 | About 200 lines of hand-written withers. A dead persistence path |
| Tests | 4/5 | 7 test classes for 11 main classes |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days.

### 2.1 Correctness

#### M1 · P1 · Each internal-core plugin node appears twice in message history — Effort S
- **Where:** `BranchProcessor`, `RecipientListProcessor`, `SplitterProcessor`,
  `ContentFilterProcessor` and `FilterProcessor` call `.withAddedHistory(nodeId)`. Core's
  `StreamTopologyDecorator.applyLoggingAndBroadcasting` then does
  `stream.map(msg -> msg.withAddedHistory(nodeId))` for **every** node.
- **Impact:** The history reads `[trigger, filter1, filter1, branch1, branch1, …]`. Any lineage or
  UI view built on it is wrong.
- **Fix:** Core owns history stamping. Delete the plugin-side calls. Document "plugins must not
  stamp history" in `Message.withAddedHistory`.

#### M2 · P1 · Null metadata or header values throw an NPE — Effort S
- **Where:** The `DefaultMessage` compact constructor calls `Map.copyOf(metadata)`, which rejects
  null values. `withHeader` copies into a `ConcurrentHashMap`, which rejects null keys and values.
- **Impact:** An HTTP trigger header with no value, or a JSON body field set to `null` that is
  copied into metadata, fails the whole node stream.
- **Fix:** Either reject nulls at the boundary with a clear message, or drop null-valued entries:
  `metadata.entrySet().stream().filter(e -> e.getValue() != null)…`. Pick one rule and document it.
  In `withHeader`, use `new HashMap<>(metadata)`. The constructor copies again anyway.

#### M3 · P2 · `timestamp` exists in two representations and `from()` loses precision — Effort S
- The record stores an `Instant`, `getTimestamp()` returns epoch millis, and `from()` rebuilds with
  `Instant.ofEpochMilli`. That drops sub-millisecond precision and allocates on every call.
  `SplitterProcessor` does the same.
- **Fix:** Expose only `Instant timestamp()`, or keep millis internally. Copy the `Instant`
  directly in `from()`.

#### M4 · P3 · `withHeader` copies twice — Effort S
- `new ConcurrentHashMap<>(metadata)` → `withMetadata` → `Map.copyOf`. Use one plain `HashMap`.

### 2.2 Performance

#### M5 · P2 · History stamping costs O(n) per hop, so O(n²) per message — Effort S
- `withAddedHistory` copies the full `List<String>` into a new `ArrayList`, then `List.copyOf` in the
  constructor copies it again. That's two copies per node per message. In `LoopStreamProcessor`
  cycles the history also grows without bound.
- **Fix:** After M1 halves the calls, cap the history (keep the last 64 entries, for example) or
  make it opt-in. If lineage matters long-term, store `(prevHistoryRef, nodeId)` as a
  linked cons-list so each append is O(1).

#### M6 · P3 · Every `with*` rebuilds all 22 fields — Effort S
- That's acceptable for an immutable record, but several call sites chain 4–7 withers per message
  (`SplitterProcessor.createChild`, routers). A `toBuilder()` (Lombok `@Builder(toBuilder = true)`
  on the record) makes multi-field updates a single allocation.

### 2.3 Simplification

| ID | Finding | Fix | Effort |
|---|---|---|---|
| M7 | About 200 lines of hand-written copy constructors (`withAddedHistory`, `withSequence`, `withControl`, `withFailure`, `withRetryCount`, `withPayload`, `from`) | Put `@With` on every record component plus `@Builder(toBuilder = true)`. The composite ones become one-liners, for example `toBuilder().sequenceId(s).sequenceNumber(p).sequenceSize(t).build()`. `withPayload` changes the type parameter, so it stays hand-written (or uses a static `from`) | S |
| M8 | `Message` has 44 methods, and each field is exposed twice (`getX()` and the record's `x()`). `DefaultMessage` is the only implementation | Short term: drop the `getX()` duplicates in favour of record accessors. Long term: make `Message` a sealed interface permitting `DefaultMessage`, or make it the record itself | M |
| M9 | `PersistingNodeMessageChannel*` (3 classes) and `MessageStore` have no implementation anywhere | Delete them until a store exists. If kept: use `concatMap` instead of `flatMap` (ordering), reuse one channel instance instead of one per node, and move the bean to `@AutoConfiguration` so `@ConditionalOnBean` is reliable | S |
| M10 | Message IDs are kept across `withPayload` and `from()`, so a store keyed by `getMessageId()` would overwrite every node's output with the same key | Document that the ID is the logical message ID, and key a future store by `(executionId, nodeId, messageId)` | S |
| M11 | `api(libs.spring.boot.starter.webflux)` for a module that needs `reactor-core`, `spring-context` and boot-autoconfigure annotations | Narrow the dependencies. That also narrows `plugin-api`, which re-exports this module | S |
| M12 | `isLastInSequence()` and `withIncrementedRetry()` have no callers outside tests | Keep them only if the aggregator or retry features are planned | S |
| M13 | `CLAUDE.md` describes a persisting channel as a live option | Update it after M9 | S |

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Correct lineage** (½ day) | M1, M2 | Accurate history and no null crashes |
| **2. Shrink** (1 day) | M7, M9, M11, M13 | About 300 fewer lines and a lighter dependency |
| **3. Hot path** (½ day) | M5, M6, M3, M4 | Fewer allocations per message per node |
| **4. API cleanup** (optional) | M8, M10, M12 | One accessor style and a clear ID semantic |
