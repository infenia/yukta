---
paths:
  - "src/**/service/worker/**"
  - "src/**/service/leader/**"
---

# Remote workers and high availability

Reference for workers (phases 1 and 2) and the active/standby leader lease. Moved from `core/CLAUDE.md`.

### Workers (remote workers, phases 1 and 2)
`WorkerRegistry` (`service/worker`) lists the connected workers; a registration or heartbeat renews a
lease (`yukta.workers.lease`, default 15s), a worker that misses it is dropped, `GET /api/workers` and
the gauges `yukta.workers` and `yukta.worker.slots` report them. The optional `workers` module (build with
`-Pyukta.workers=true`) carries them over RSocket: the control plane sets `yukta.workers.token` and
`spring.rsocket.server.port`; a worker sets `yukta.workers.control-plane=host:port`, `yukta.workers.token`,
`yukta.workers.id` (and `pools`, `plugin-types`, `capacity`) and connects out, registers on the route
`worker.session` and renews its lease, reconnecting with a growing pause. The connection can use mutual TLS: the control plane sets `spring.rsocket.server.ssl.bundle` and `spring.rsocket.server.ssl.client-auth=need`, a worker sets `yukta.workers.ssl-bundle` (an SSL bundle with its key store and the trust store for the control plane; the host name in the certificate is verified), and the shared token still has to match. `yukta.workers.control-plane` may list several addresses, comma separated: each connection attempt tries the next, and a standby (`LeaderElection.isLeader()` false) refuses the registration, so a worker ends up on the leader and finds the new one after a failover (with the growing pause between attempts). A processor node with
`engine.placement {pool}` runs on a worker of that pool: `WorkflowPreparator` swaps its plugin for a
`RemoteProcessor` (`RemoteNodes`; the plugin on this server still provides type, schema, timeouts and
validation), so retries, `onError`, timeouts, valves, checkpoints and status work as for a local node.
Triggers, terminals and the stateful plugins (`SESSION_MEMORY`, `KV_STORE`, `WAIT_FOR_SIGNAL`,
`SUB_WORKFLOW`) cannot be placed, and without the `workers` module a placed node fails to prepare. The control
plane sends each attempt down the worker's own RSocket connection (`node.run`, one `requestChannel`: START,
the input messages, COMPLETE; back come the output, HEARTBEATs that restart the node's idle clock, and COMPLETE
or ERROR), so back-pressure and cancellation work end to end. It chooses the connected worker of the pool that
allows the plugin type and has the most free slots (counting what it has sent and the worker's heartbeat); none
means the attempt fails at once, as a failure the engine can retry. A worker that goes away mid-attempt (its connection closes, also when the agent is stopped) fails the attempt with a `WorkflowExecutionException` naming it; the engine's retry picks a worker again, so it can land on another one. A stopping worker drains first: its agent tells the control plane it takes nothing new (`draining` on the lease frame, so `WorkerNodeExecutor` skips it), waits up to `yukta.workers.drain-timeout` (30s) for its running attempts, then closes the connection; what is still running then fails as a lost worker. `LLM_CALL` counts `llm.tokens{provider,direction}` and `llm.cost{provider}`. A worker runs only the plugin types on
`yukta.workers.plugin-types`, initialises and prepares the plugin per attempt, and sends a failure back with
its class name (`IllegalArgumentException` stays one, so it is not retried). Messages cross as plain JSON values.
See `../design/plans/remote-workers.md`.

### High availability (active/standby)
`LeaderElection` (`service/leader`) says whether this server admits runs. A single server is always the
leader (`SingleServerLeader`). With `yukta.ha.mode=postgres` (persistence-postgres, `V4__leader_lease`) servers share
the database and one holds a lease row (`yukta.ha.lease`, default 15s, renewed three times per lease; a graceful stop
releases it; the `yukta.leader` gauge is 1 on the leader; the fencing number grows with every change of holder). A
server counts itself the leader only while its own last renewal is younger than the lease, so one that cannot reach
the database stops before another can take over. `ExecutionScheduler` refuses every start, restart and resume on a
standby with `NotLeaderException` (HTTP 503; counted as `yukta.executions.rejected{reason=not_leader}`). Startup
recovery is skipped in this mode; on takeover `TakeoverRecovery` runs `ExecutionRecovery.recover()` (marks the old
leader's unfinished runs `ABORTED`). With `yukta.restart.auto-resume=true`, after any recovery (startup of a
single server too) `ExecutionResumer.resume` runs on each interrupted run, one after the other, from the
checkpoints (use the PostgreSQL checkpoint store, or a shared directory). Lost on takeover: live approval waits, queued
starts, slots and the old tracker state. Clients send requests to the leader; there is no proxy.
