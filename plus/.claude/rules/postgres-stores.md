---
paths:
  - "persistence-postgres/**"
---

# PostgreSQL stores (`plus/persistence-postgres`, optional, AGPL)

Moved from `core/CLAUDE.md`; loads when you touch `plus/persistence-postgres`.

### PostgreSQL stores (`plus/persistence-postgres`, optional, AGPL)
`yukta.session.store-type=postgres` (profile `postgres`, build with `-Pyukta.persistence=postgres`)
selects `R2dbcSessionConfigStore`, `R2dbcWorkflowDefinitionStore` (definitions plus the newest 20
versions) and `R2dbcExecutionLogStore` (reactive reads, one serial write pipeline that a read only waits on to drain the events before it, then queries outside it, a `SmartLifecycle` that flushes on stop; trims to the newest
`retained-runs` runs and never drops a run without an end). Schema: one Flyway migration, `V1__yukta_core.sql` (the alpha schema, squashed again on 2026-11-24: recreate an older database; while alpha, change V1 rather than adding V2). `execution_log` repeats the event `type` and `ts` as columns (indexes `(execution_id, seq)`, `(type, seq)`, partial `execution_log_run_edge` on run starts and ends, which the log trim reads instead of the whole log); `input_request` has partial indexes `input_request_due` (`LEAST(deadline, tier_deadline)`), `input_request_answered` and `input_request_purge`, so queries must repeat their predicates. In
`plus/persistence-postgres/src/main/resources/db/migration` (applied over JDBC from `spring.flyway.url`);
run time uses R2DBC (`spring.r2dbc.url`). The in-memory defaults step aside when the type is
`postgres`. Node checkpoints use `R2dbcNodeCheckpointStore` when `yukta.restart.store-type=postgres` (one row per message in JSONB, which PostgreSQL stores out of line, so no cap; `saveAll` writes a batch in one fenced `INSERT ... FROM unnest(text[])`, one statement text for every size; the log's `batchInsert` does the same with four parallel arrays; a state row per node holds the end marker and the incomplete flag, fenced too, read in one query; `clearAsync` is one transaction), otherwise memory. Input requests use `R2dbcInputRequestStore` (tables `input_request`, `input_response`) when `yukta.input.store-type=postgres`; `InputRequestStoreContract` (plus testkit) runs against it in `R2dbcInputRequestStoreContractTest`. A change is one `UPDATE ... WHERE version = :expected`; `dueBefore` takes no row locks, because the broker changes a request only by compare-and-set. `input_response` has no foreign key (the store keeps an answer whatever became of its request; the purge deletes both). Supervisors use `R2dbcSupervisorStore` (tables `supervisor`, `supervisor_attachment`; versions in `supervisor_version`: a version is numbered `MAX+1` in the INSERT and skipped when its jsonb body equals the newest, a primary-key clash is retried).
