# Remote workers: efficient, scalable, deployable

Plan for making remote workers (ADR [0006](../decisions/0006-remote-workers.md), module `plus/workers`, core
`service/worker`) carry real fleets, from a few machines on one network to Kubernetes and ECS, with the least work on
the control plane, the fewest network round trips and the fewest bytes on the wire.

Status (2026-11-27): **accepted, nothing built.** The "today" statements were read from `origin/main` at e95fc852c.
Arun decided D1 to D5 on 2026-11-27.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Connected placed nodes | **Auto-fuse, opt-out.** A connected run of placed nodes in the same pool runs as one segment on one worker; `engine.placement.fuse: false` keeps a node apart. Each node still reports its own status and checkpoints. |
| D2 | Wire contract and languages | **JVM workers only; keep RSocket `requestChannel`.** No protobuf and no gRPC: message payloads are arbitrary JSON, so protobuf would only shrink the small envelope. |
| D3 | Compression | **JDK `Deflater`/`Inflater` per channel** (level 1, `SYNC_FLUSH` per frame), agreed at `REGISTER`. No new dependency, works in a native image. Frames with the token are never compressed. |
| D4 | A full pool | **Queue with a timeout** (per pool, fair across runs), then today's retryable error; opt-in `engine.placement.fallback: local`. |
| D5 | Deployment targets | **Kubernetes + KEDA, AWS ECS, Compose / VMs (systemd)**, as files in `deploy/` and sections of the remote-workers guide. |

## What exists today

Built: outbound RSocket from worker to control plane, token (also per worker, `yukta.workers.tokens.<id>`) and mTLS,
REGISTER/LEASE with a lease, pool + plugin-type placement, most-free-slots choice with atomic slot reservation
(#2748), at-least-once with retry when a worker is lost, drain, leader discovery over several addresses, a protocol
version check, live log lines (LOG frames, #2749) and trace headers on START (#2750), per-role native images (#2752),
nodes with a run budget kept local (#2751), the Workers panel.

| # | Gap | Where | Impact |
|---|---|---|---|
| G1 | Every placed node is its own attempt: A→B→C in one pool goes worker→control plane→worker→control plane→worker | `RemoteNodes.place`, `WorkerNodeExecutor.run` | 2 hops per edge, the control plane carries every byte, a round trip of latency per node |
| G2 | The worker calls `initialize` + `prepare` per attempt and never `shutdown`; `RemoteProcessor.initialize/prepare` also initialise the plugin on the control plane | `NodeRunner.serve`, `RemoteProcessor` | HTTP/SQL/LLM clients rebuilt per attempt and leaked, and duplicated on the control plane |
| G3 | Messages are converted `Message`→`Map`→CBOR on both sides (the codec is already CBOR, W0 states it); no compression; one frame per message | `WorkerNodeExecutor.frame`, `NodeRunner` | double conversion; repeated keys and text payloads cross uncompressed |
| G4 | No claim-check (ADR 0006 open item); a 64 MB payload passes through the control plane | `WorkerTransport.inboundLimit` | the control plane is a data hop and a memory risk |
| G5 | No worker with a free slot → the attempt fails at once | `WorkerNodeExecutor.run` | burns retries; nothing to autoscale on; no scale to zero |
| G6 | `Placement(pool)` only (ADR's `requires` not built); a slot is any plugin | `NodePolicy.Placement` | no GPU/arch/zone targeting; 4 builds can land on a 2-CPU pod |
| G7 | A retry may pick the worker that just failed; a worker that fails fast has the most free slots and attracts work | `WorkerNodeExecutor.pick` | "black-hole" worker |
| G8 | Heartbeat and log frames go into an unbounded unicast sink, one frame per call; prefetch counts messages, not bytes | `NodeRunner.serve` | frame spam, memory spikes with large messages |
| G9 | `WorkerFrame.PROTOCOL` must be equal | `WorkerSessionHandler.admissionProblem` | no rolling upgrade |
| G10 | START has no attempt number or idempotency key (ADR D7) | `NodeFrame.start` | plugins cannot de-duplicate |
| G11 | `workerId` is not bound to the client certificate; one token, no rotation; plugin versions not compared | `WorkerSessionHandler` | impersonation, silent version skew |
| G12 | A worker boots the whole server (web, UI, persistence, scheduler, MCP) | `boot`, `WorkersAutoConfiguration` | slow start, large pods |
| G13 | No pool demand metrics, no per-worker bytes or dispatch latency, no deployment files | `WorkerRegistry` gauges only | cannot autoscale or prove efficiency |

## Phases

One PR per phase, stacked in this order; each ends green on `yk check`.

### W0 Measure first
- Meters: `yukta.worker.bytes{worker,direction}`, `yukta.worker.frames{kind}`, `yukta.worker.dispatch` (pick to first
  frame), `yukta.worker.attempts{worker,outcome}` in `WorkerNodeExecutor` and `NodeRunner`.
- A test that states which data MIME type the RSocket strategies use today (it is `application/cbor`).
- A perf scenario: `perf/compose.yaml` gains two workers, `perf/k6/workers.js` runs a 3-node placed chain with 1 KB and
  1 MB messages; bytes per message and p50/p99 node latency go into `perf/BASELINE.md`. Later phases report against it.

### W1 Plugin reuse and attempt identity (G2, G10)
- `NodeRunner` keeps a bounded cache of prepared plugin instances keyed by `(type, hash(config))`, built with
  `plugin.forNode(config)`; `initialize`/`prepare` once, `shutdown` on eviction and on drain (`WorkerAgent.stop`).
- `RemoteProcessor.initialize/prepare` do nothing (validation stays on the control plane).
- START gains boxed `attempt` and `idempotencyKey` (`executionId:nodeId:attempt`); the worker puts them in the Reactor
  context for plugins.

### W2 Bounded frames (G8)
- Heartbeats throttled to one per `yukta.workers.heartbeat-every` (1s) per attempt; log lines batched; the sink is
  bounded (drop the oldest heartbeat, never a log line without counting it).
- `limitRate` on both directions from `yukta.workers.prefetch` (default 32).

### W3 Worker health (G7)
- A retry skips the worker that failed the previous attempt of the same node when another one qualifies.
- A worker with `yukta.workers.quarantine.failures` (5) non-input failures in a row is skipped for
  `yukta.workers.quarantine.cool-down` (60s); `GET /api/workers` shows it (refresh the OpenAPI snapshots).

### W4 Rolling upgrades and identity (G9, G11)
- REGISTER carries `protocolMin/Max`, `capabilities` (`deflate`, `batch`, `segment`) and `pluginVersions`; the control
  plane accepts an overlap and uses the highest common version; placement needs the same plugin major version.
- With mTLS the `workerId` must equal the certificate's CN or a DNS SAN; `yukta.workers.token` takes a comma list
  (old and new) for rotation.

### W5 Queueing (G5)
- No free slot: the attempt waits in a per-pool queue, round-robin across executions, up to
  `engine.placement.queueTimeout` (default 5m), then fails retryably as today. A released slot or a lease wakes it.
- `engine.placement.fallback: local` runs it on the control plane after the wait.
- Gauges `yukta.pool.pending{pool}`, `yukta.pool.slots{pool,state}`, timer `yukta.pool.wait`.

### W6 Targeting and affinity (G6)
- `Placement(pool, requires, weight, affinity)`: `requires` matches `yukta.workers.labels`; `weight` slots per attempt
  (default 1); worker caps per plugin type `yukta.workers.limits.<TYPE>`, sent in REGISTER.
- `affinity` (an expression) picks a worker by rendezvous hash among those that qualify, falling back to the most free
  one when it is full; warm caches for CI nodes.

### W7 Wire format (G3)
- Encode `Message` directly with the Jackson CBOR mapper (CBOR is already the codec); no `Map` hop.
- With `deflate` agreed, one `Deflater`/`Inflater` pair per `node.run` channel, level 1, `SYNC_FLUSH` per frame;
  frames under 512 B raw (flag). The `worker.session` stream (token) is never compressed.
- A MESSAGE frame may carry a list: the sender batches only what is already waiting (up to 64), so a lone message is
  not delayed.

### W8 Claim-check (G4)
- Above `yukta.workers.claim-check-bytes` (1 MB) a payload goes to a blob store and only `{ref, size, sha256}` crosses;
  the receiver fetches it. Stores: shared directory, then PostgreSQL (`plus/persistence-postgres`); S3 when asked for.
  The leader deletes expired blobs.

### W9 Segment fusion (G1)
- Planner (`WorkflowPreparator` / `RemoteNodes`): maximal connected sub-DAGs of nodes placed in the same pool with
  compatible `requires`, none local-only (`requiresControlPlane()`, run budget), none with `fuse: false` or a
  breakpoint. Each becomes one `RemoteSegment` stub; edges leaving it stay on the control plane.
- START gains `segment` (nodes, internal edges, per-node config and policy); the worker assembles it with the core
  engine (`ProcessorNodeAssemblerStrategy`), so per-node retries, timeouts and merges run there.
- Back come `NODE_STATUS` and `CHECKPOINT` frames; pause and skip go down as `SIGNAL` frames. A lost worker retries the
  segment from its last checkpointed node. Internal edges never use the claim-check.

### W10 Lean worker (G12)
- A worker (`yukta.workers.control-plane` set) runs without the web application (only `management.server.port` for
  health and metrics), persistence, scheduler, MCP and UI.
- Health groups: readiness = registered with a leader, liveness = agent alive; drain turns readiness off first.

### W11 Deployment and autoscaling (G13)
- `GET /api/workers/pools` (pending, used, free per pool) for the KEDA metrics-api scaler without Prometheus (refresh
  the OpenAPI snapshots).
- `deploy/helm/yukta`: control plane (readiness = leader, so the Service reaches only the leader) and one worker
  Deployment per pool (pool, labels, limits, capacity, resources), PodDisruptionBudget, `preStop` drain,
  `terminationGracePeriodSeconds` above the drain timeout, mTLS secrets, a KEDA `ScaledObject` on pending + used
  (minimum 0).
- `deploy/ecs` (task definitions, `stopTimeout` tied to the drain, service autoscaling on the same numbers through
  CloudWatch), `deploy/compose`, `deploy/systemd/yukta-worker.service`; sections in
  `docs/guides/operate/add-remote-workers.mdx`.

### W12 UI and docs
- `WorkersPanel.tsx`: used/limit per plugin type, bytes in and out, quarantined and draining, queue per pool.
- ADR 0006 (D1-D5 above), `plus/workers/CLAUDE.md`, `core/.claude/rules/workers-and-ha.md`,
  `docs/contributing/modules/workers.mdx`.

## Not in this plan
Zone/RTT-aware choice (after labels, when multi-region users ask), speculative hedging of slow attempts (when a p99
tail is measured), gRPC/protobuf for non-JVM workers (D2), direct worker-to-worker streams (W8 + W9 cover the data
path), queued starts surviving a leader change (HA plan).

## Verification
- Each phase: `yk gradle :plus:workers:test`, `:core:test --tests 'com.infenia.yukta.service.worker.*'`,
  `-Pyukta.workers=true :boot:test --tests '*Workers*'`, `:e2e:test --tests '*WorkersE2eTest'`, then `yk check`.
- Efficiency against W0: at least 3x fewer bytes per 1 KB text message (W7); no control-plane bytes for the internal
  edges of a fused chain and at least 50% lower p50 for a 3-node chain (W9); flat control-plane heap with 10 MB
  payloads (W8).
- Resilience: `ChaosTest` killing a worker mid-attempt and mid-segment; protocol N with N-1 peers; a worker that always
  fails gets quarantined.
- Deployment: `helm template` and a kind cluster with two pools: KEDA scales a pool from 0 when a placed run starts;
  deleting a pod drains it without a failed run.

## Issues
Filed from this plan with `yk ticket from-plan design/plans/remote-workers-efficiency.md`.
