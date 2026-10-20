# Plus

Single-node durable storage on disk, licensed **AGPL-3.0-only** (see [`LICENSING.md`](../LICENSING.md)).

Added to the application with `-Pyukta.edition=plus`:

| Setting | Stores |
|---|---|
| `yukta.session.store-type=file` | sessions and workflow definitions (`yukta.session.base-dir`) |
| `yukta.execution-log.dir` | the execution log (JSONL) |
| `yukta.restart.dir` | node checkpoints, for resuming after a restart |
| `yukta.session-memory.dir` | chat history of `SESSION_MEMORY` nodes |

Rules for this module: nothing outside `boot` may depend on it, and every file keeps the
`AGPL-3.0-only` SPDX header (Spotless adds it).
