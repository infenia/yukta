# Performance plan

Plan for unbounded memory growth, hot paths and design limits in core, with a baseline step, effort and severity ratings, and decisions needed.

Work on this plan is tracked in its Epic issue on the Yukta Roadmap project (search for `performance plan`).

Source: the Performance, Scalability and Availability risks in the core module guide (see [risks](https://github.com/infenia/yukta/blob/6e7d2d9567ed1436904dda7d7c21473b3b062bba/docs/internal/risks.mdx#core)), checked
against `main` by reading the cited code (2026-10-13). **Nothing was measured or run.** `Conf`:
`read` = the lines show the behaviour; `measure` = the cost is real only if the benchmark in step 0
says so, so do that first. Effort and severity as in [the remediation plan](./remediation.md): **S** up to half a
day, **M** 1-3 days, **L** a week or more; Sev C/H/M/L.

Memory growth that anyone can trigger is also a denial-of-service hole, so the unbounded structures
here are marked `sec` and ranked with the security work.

## Step 0: a baseline before any tuning (S)

One workflow, 20 processor nodes (as `StreamTopologyDecoratorBackpressureTest`), 100k small messages,
then the same with `yukta.restart.dir` set. Record messages per second, p99 node latency and heap
after a forced GC. Without these numbers items marked `measure` stay open. Add `yukta.tracker.queue.depth`
(gauge on the unbounded event queue, risk `trackerQ26`) at the same time; it costs a few lines and tells
you whether the tracker keeps up.

## P0: unbounded growth (fix with the P0 security PRs)

| ID | Sev | Eff | Conf | Evidence | Issue | Fix | Test to add | Brk |
|---|---|---|---|---|---|---|---|---|
| ~~B34~~ fixed `sec` | H | S | read | `service/streaming/DefaultStatusHistoryCache.java:55,67-69` (Caffeine with `expireAfterWrite` only, `ConcurrentLinkedDeque.addLast`); `DefaultTaskTrackerService.java:906-912` (a full `WorkflowProgress`, copying every task, per status event) | Each run keeps one full snapshot per status event for up to 30 minutes, no count cap on either the deque or the cache. A 500-node run with 3 events per node holds about 1500 snapshots of 500 tasks each; many runs multiply it. | Cap the deque (keep the newest N, always keep the last terminal one) and set `maximumSize` on the cache; optionally coalesce snapshots (`sample`) for the live sink but never drop the terminal one | A run with more events than the cap: history size is bounded and still ends with the terminal snapshot | mild (late joiners see fewer intermediate steps) |
| ~~B28~~ fixed `sec` | M | S | read | see the [remediation plan](./remediation.md) | Default execution log never trims | see the [remediation plan](./remediation.md) | see the [remediation plan](./remediation.md) | no |
| ~~B31~~ fixed `sec` | L | S | read | see the [remediation plan](./remediation.md) | Idempotency cache has no size cap | see the [remediation plan](./remediation.md) | see the [remediation plan](./remediation.md) | no |
| B21 `sec` | L | S / M | read | see the [remediation plan](./remediation.md) | Session entries are never removed | see the [remediation plan](./remediation.md) | see the [remediation plan](./remediation.md) | no |
| B35 `sec` | M | S | inferred | not located yet; check `yukta.start` payload, workflow definition and message sizes for a limit at the web edge and in `WorkflowValidator` (500 nodes and 2000 edges are capped, bytes are not) | A large start payload or definition may be accepted and then copied into the log, archive, checkpoints and snapshots. | Find the current limits first; add `yukta.limits.max-start-payload-bytes` and a definition size cap, answer 413 | Over-limit start and over-limit definition are refused with 413; at the limit they pass | mild |

## P1: hot paths (all measure unless noted)

| ID | Sev | Eff | Conf | Evidence | Issue | Fix | Test to add |
|---|---|---|---|---|---|---|---|
| P1 | M | M | read, measure | `service/store/FileNodeCheckpointStore.java:198-210` (`createDirectories`, `Files.writeString` with `CREATE, APPEND`, one `boundedElastic` hop, per message) | With `yukta.restart.dir` set every message costs a directory check, an open, an append and a close on another thread, plus a `getBytes` for the counter. File mode has no byte caps, so this is the throughput ceiling of a durable run. | One open appender per (run, node) kept until `markComplete`; batch with `bufferTimeout`; encode once | The step 0 benchmark with and without the change; output file byte-identical |
| P2 | M | S | read | `FileNodeCheckpointStore.java:236-250` (`Files.readAllLines`, then a list of all messages) and `getAll` | A restart or resume loads a node's whole recorded output into memory, although `getAll` returns a `Flux`. In file mode there is no cap, so a large node can exhaust the heap on resume. | Read line by line as a `Flux.using` stream | Resume a node with a large output under a small heap |
| P3 | L | S | read | `plus/.../service/log/JsonlExecutionLogStore.java:271-283` (`readAllLines` at startup and compaction) | The log is read whole. Bounded by `retained-runs` and compaction, but one run with a huge payload line still costs its size several times. | Stream the file; combine with the payload limit B35 | Startup with a large log under a small heap |
| P4 | M | M | measure | `DefaultTaskTrackerService.java:137,237-238` (one unbounded sink, `bufferTimeout(100, 50 ms)`); `ControlBusService.java:82,151-152` | State events go through one queue and one consumer; log lines are dropped beyond 10000 but state events never are, so a slow consumer turns into heap growth (risk `trackerQ26`). | The gauge from step 0 first; then coalesce progress events per node; decide drop versus grow explicitly | Step 0 benchmark: queue depth stays bounded at the target rate |
| P5 | L | M | measure | `JsonlExecutionLogStore` writer thread; `R2dbcExecutionLogStore` (risk `writerThread27`) | One writer thread for the log; PostgreSQL goes through it too. | Batch inserts; reactive interface later | Event rate the writer sustains vs. the tracker's rate |
| B16 | M | M-L | read | see the [remediation plan](./remediation.md) | A dead branch stalls siblings until the idle timeout, which holds a slot and the memory of the buffered messages | see the [remediation plan](./remediation.md) | see the [remediation plan](./remediation.md) |
| B19 rest | L | S | read | see the [remediation plan](./remediation.md) | Blocking archive on the sub-workflow start path | see the [remediation plan](./remediation.md) | see the [remediation plan](./remediation.md) |

## P2: design decisions that cap scale

| ID | Eff | What | Decision needed |
|---|---|---|---|
| D1 (`backpressure25`) | M | The slowest child paces every fan-out. Add an explicit per-edge buffer or drop policy for non-critical branches (an audit sink). | Which consumers may fall behind, and may they lose data? |
| D2 (`payload23`) | L | Large payloads travel through the engine, the checkpoints and the snapshots. Claim-check references plus a frame size limit; tied to F10 and to remote workers. | Largest message expected in a year; object store or PostgreSQL large objects |
| D3 (`spof`, `remote`) | L | Single process, no scale-out of node execution. F10 then remote workers then HA, as in the guide's evolution path. | See [the remote workers plan](./remote-workers.md) |
| D4 (`limits28`) | S | Runtime limit changes are lost on restart. Reload from the log or say configuration is the source of truth. | Which one |

## Order

1. Step 0 (baseline plus the queue depth gauge).
2. **PR-A** B34 + B28 + B31 + B35 together with security PR-2 of [the remediation plan](./remediation.md): same theme, bound every in-memory structure.
3. **PR-B** P2 + P3 (streaming reads), then P1 only if step 0 shows the append cost.
4. P4 and P5 only if the gauge or the benchmark says so; D1-D4 as product decisions.

Run the narrowest tests per `CLAUDE.md` tiers; each PR ends with `spotlessApply check` once.
