---
paths:
  - "persistence-postgres/**"
---

# PostgreSQL stores (`plus/persistence-postgres`, optional, AGPL)

Moved from `core/CLAUDE.md`; loads when you touch `plus/persistence-postgres`.

### PostgreSQL stores (`plus/persistence-postgres`, optional, AGPL)
`yukta.session.store-type=postgres` (profile `postgres`, build with `-Pyukta.persistence=postgres`)
selects `R2dbcSessionConfigStore`, `R2dbcWorkflowDefinitionStore` (definitions plus the newest 20
versions) and `R2dbcExecutionLogStore` (blocking interface, so one writer thread; trims to the newest
`retained-runs` runs and never drops a run without an end). Schema: Flyway migrations in
`plus/persistence-postgres/src/main/resources/db/migration` (applied over JDBC from `spring.flyway.url`);
run time uses R2DBC (`spring.r2dbc.url`). The in-memory/file defaults step aside when the type is
`postgres`. Node checkpoints use `R2dbcNodeCheckpointStore` when `yukta.restart.store-type=postgres` (one row per message in JSONB, which PostgreSQL stores out of line, so no cap; a state row per node holds the end marker and the incomplete flag; migration V3), otherwise files (`yukta.restart.dir`) or memory. `KV_STORE` keeps its state in `R2dbcKeyValueStore` (table `kv_entry`, migration V5) when `yukta.kv.store-type=postgres`; the SPI is `KeyValueStore` in plugin-api. Input requests use `R2dbcInputRequestStore` (tables `input_request`, `input_response`, `input_delivery`, migration V9) when `yukta.input.store-type=postgres`; `InputRequestStoreContract` (plus testkit) runs against it in `R2dbcInputRequestStoreContractTest`. A change is one `UPDATE ... WHERE version = :expected`; `dueBefore` takes no row locks, because the broker changes a request only by compare-and-set. `input_response` has no foreign key (the store keeps an answer whatever became of its request; the purge deletes both). Supervisors use `R2dbcSupervisorStore` (V7, V8, V10–V12, V16; versions in `supervisor_version` and the hold ledger in `supervisor_hold`, V17: a version is numbered `MAX+1` in the INSERT and skipped when its jsonb body equals the newest, a primary-key clash is retried). `SESSION_MEMORY` history goes to `R2dbcSessionHistoryStore` (table `session_history`, migration V6) with `yukta.session-memory.store-type=postgres`; the SPI is `SessionHistoryStore`.
