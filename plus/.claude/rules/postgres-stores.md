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
`retained-runs` runs and never drops a run without an end). Schema: Flyway migrations (`V1`, the alpha squash; `V2` drops the `KV_STORE` and `SESSION_MEMORY` tables) in
`plus/persistence-postgres/src/main/resources/db/migration` (applied over JDBC from `spring.flyway.url`);
run time uses R2DBC (`spring.r2dbc.url`). The in-memory/file defaults step aside when the type is
`postgres`. Node checkpoints use `R2dbcNodeCheckpointStore` when `yukta.restart.store-type=postgres` (one row per message in JSONB, which PostgreSQL stores out of line, so no cap; a state row per node holds the end marker and the incomplete flag), otherwise files (`yukta.restart.dir`) or memory. Input requests use `R2dbcInputRequestStore` (tables `input_request`, `input_response`, `input_delivery`) when `yukta.input.store-type=postgres`; `InputRequestStoreContract` (plus testkit) runs against it in `R2dbcInputRequestStoreContractTest`. A change is one `UPDATE ... WHERE version = :expected`; `dueBefore` takes no row locks, because the broker changes a request only by compare-and-set. `input_response` has no foreign key (the store keeps an answer whatever became of its request; the purge deletes both). Supervisors use `R2dbcSupervisorStore` (tables `supervisor`, `supervisor_attachment`; versions in `supervisor_version`: a version is numbered `MAX+1` in the INSERT and skipped when its jsonb body equals the newest, a primary-key clash is retried).
