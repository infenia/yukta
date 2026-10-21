# Core: assessment

How the `core` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/core.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Acore), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Acore) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Acore) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

1. **Today.** One JVM. Files or PostgreSQL for definitions, sessions and log. Resume with file checkpoints.
2. **Finish PostgreSQL (F10).** Done: the checkpoint store in PostgreSQL (yukta.restart.store-type=postgres). Also done: KV_STORE in PostgreSQL and a CI step for the PostgreSQL acceptance test. Left: a PostgreSQL backend for session memory, claim-check references, native-image metadata. Risks: `payload23`.
3. **Workers phase 1-2.** Done: registry, agent, token auth, leases, remote processor over a stream with cancel and heartbeat, pool and free-slot choice. mTLS available.
4. **Placement and failure.** Pools, free-slot choice, local-only plugins, lost-worker retry, chaos tests. Risks: `remote`.
5. **Operations.** Worker UI, slot accounting, native image for workers.
6. **Control plane HA.** Done: active/standby with a PostgreSQL lease, a standby refuses starts, takeover recovers and resumes interrupted runs. Left: queued starts and live waits survive. Workers listing several control planes find the new leader by themselves. Risks: `spof`.

## Quality lenses

### Scalability

Vertical on one JVM. Streams are efficient, but every run's state, the scheduler and the tracker share the process. Horizontal scale-out is designed (remote workers) but not built.

**Ceilings today**

- All admission, ordering and run state in one process
- Unbounded tracker state queue; sessions are evicted only if yukta.tracker.max-sessions or yukta.tracker.session-ttl is set
- Fan-out paced by the slowest child
- Checkpoint memory bound = retained runs x nodes x bytes-per-node
- The default in-memory execution log and the idempotency cache are not size bounded

**Levers today**

- Global, per-workflow and per-session limits and fair queue
- Byte and message caps on checkpoints
- Virtual threads for blocking plugins

**Needs design work**

- Remote workers (F11 phases 1-5)
- A time-based eviction policy for finished sessions
- Shared control state (phase 6)

### Performance

Low per-message overhead: one volatile write per activity touch, one periodic idle check per node, batched tracker consumer.

**Ceilings today**

- Per-message retry or ROUTE buffers a message's output
- Large payloads copy through memory and checkpoints
- Single writer thread for the log
- Compile cost grows with graph size (cap 500 nodes)

**Levers today**

- Back-pressure instead of queues
- Prepared-workflow cache and shared prepare
- Batching in tracker and log

**Needs design work**

- Claim-check for large payloads
- Defer blocking archive on the start path
- Performance budget per node type

### Availability

A crash is recoverable, not invisible: runs are ABORTED and resumable only with a file or PostgreSQL checkpoint store. There is no standby.

**Ceilings today**

- Control state in memory is a single point of failure
- Checkpoints not in PostgreSQL
- Signal waits and queue positions are lost
- Log events not fsynced unless the option is on

**Levers today**

- Durable node end marker
- Boot recovery and one-shot resume
- Idempotent start surviving restarts

**Needs design work**

- Checkpoint store in PostgreSQL
- Active/standby with a database lease (phase 6)
- Durable signal waits

### Security

Keys, roles, session scoping, host and origin checks, command review, tool and prompt filters and expression limits are in place.

**Ceilings today**

- Plugins run in the engine JVM
- Scripts have a time, a statement, a value-count and a string-length limit but no heap limit (the runtime has no heap limit option)
- Filters are patterns, not a classifier

**Levers today**

- REST and MCP roles, hashed, rotating and session-limited keys, actors in the audit
- Key checks on the decoded path, one key set for REST and MCP, Host and Origin check, protected actuator
- Deny-by-default process execution; allow-listed env, script, expression and tool surface; prompt and answer filters
- Reviewers and audit on limit and priority changes

**Needs design work**

- Worker mTLS and plugin allow-lists (remote design)

### Operability

Good visibility, thin lifecycle tooling.

**Ceilings today**

- Run history search covers the retained runs of each session (GET /api/executions), not older ones
- Sessions are evicted only by a count, not by age

**Levers today**

- Meters for every governance decision
- Run and node spans
- SSE feed and supervisors
- A list of pending approvals (REST, MCP, workflow page) and a versions panel with diff and rollback
- Limits changed at run time restored on start

**Needs design work**

- Health and pool metrics for PostgreSQL (check what Spring Boot already exposes)
- Run history search beyond the retained runs
- Parent/child links in history (only the start payload carries them)

### Evolvability

Sealed command types and a plugin API keep the surface small, but adding a command type is an API change.

**Ceilings today**

- Sealed ExecutionControlCommand in plugin-api
- Large tracker class
- Validator and assembler disagree on what a terminal is

**Levers today**

- Records and small processors per command
- Property-based tests on status order
- Coverage and static-analysis gates

**Needs design work**

- Extension point for new command types
- Split tracker projections

## Questions to settle

- **Failure.** One terminal fails while its sibling is slow. What happens to the sibling, and what is the run's final status? (risk `backpressure25`)
- **Scale.** Your largest customer starts 5,000 runs in a minute with no limits set. What happens first, and what is the first graph you look at? (risk `spof`)
- **Durability.** You pull the power. Which approvals, queued starts and limit changes are gone, and do users find out? (risk `signals14`)
- **Security.** A workflow author is not trusted. Which three plugins must be unavailable to them? (risk `remote`)
- **Evolution.** The remote-workers design needs one decision reversed in a year. Which, and what does it cost? (risk `remote`)
- **Performance.** A message becomes 50 MiB. Which components copy it, how many times? (risk `payload23`)
- **Control.** An AI supervisor pauses a run you are debugging. How do you tell, and how do you stop it? (risk `ctlKey`)
- **Data.** A run was resumed after the definition was edited. Why is that refused, and is it the right call? (risk `exactlyOnce22`)
- **Operability.** Which three signals would page you at 3 a.m. today? Which are missing? (risk `trackerQ26`)
- **Product.** If sub-workflows power agents that plan their own DAGs, which limit breaks first? (risk `gaps12`)
