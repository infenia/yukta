# ADR 0006: Remote workers

- **Status:** accepted. Registry, lease, RSocket server and agent, token and mTLS auth, remote processor execution, placement, lost-worker retry, drain and the kill -9 chaos test are built. Worker UI, slot accounting, a native-image worker and the last part of control-plane HA are open.
- **Date:** 2026-10-21 (condensed from the remote-workers plan into this ADR on 2026-11-22)
- **Decided by:** Arun.
- **Epic:** the Yukta Roadmap epic `remote-workers plan`. Module: `plus/workers` (see [ADR 0005](0005-editions.md)).

## Context

One JVM runs everything. Processor nodes that are CPU or I/O heavy (process execution, LLM calls, SQL, HTTP, scripts) cannot scale out or be isolated. The seam for remote execution already exists: `ProcessorNodeAssemblerStrategy` builds the node pipeline, and everything around the plugin call (merge of parents, valves, retries, timeouts, checkpoints, tracing) lives on the control plane.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | How to scale out | **Remote node invocation**: the control plane keeps the DAG and calls a worker for a node with `engine.placement`. Rejected: whole runs on workers (no per-node isolation, control must be proxied) and active/standby only (does not scale; kept as the HA step). |
| D2 | Transport | **RSocket `requestChannel` over TLS**, one bidirectional stream per node attempt, with end-to-end back-pressure and cancellation. Workers connect out to the control plane (no inbound ports). |
| D3 | Packaging | A worker is the same boot jar with the `worker` profile. |
| D4 | Frames | `START`, `MESSAGE` (both ways), `HEARTBEAT`, `SIGNAL`, then `COMPLETE` or `ERROR{retryable}`. JSON payloads, size cap per frame, claim-check reference above it. |
| D5 | Placement | `engine.placement: {pool, requires}`; default `local`. The scheduler picks the live worker in the pool that has the plugin type and the most free slots. Triggers and terminals always run locally. |
| D6 | Process-local plugins | `SESSION_MEMORY`, `KV_STORE`, `WAIT_FOR_SIGNAL`, `SUB_WORKFLOW` are `local-only`; validation rejects `placement` on them until their state is shared. |
| D7 | Delivery guarantee | **At-least-once.** A node attempt may run twice if a worker dies after the effect; message `id` and `attempt` are passed for de-duplication. |
| D8 | Authentication | mTLS (`yukta.workers.ssl-bundle`, `client-auth=need`) or a shared token. Registration only from authenticated workers; a worker runs only plugin types on its own allow list. |
| D9 | Secrets | `decrypted:` references resolved on the control plane and sent over TLS, or by the worker's own provider (`yukta.worker.secrets=local\|control-plane`). |
| D10 | First plugins allowed remotely | `PROCESS_EXECUTOR`, `HTTP_REQUEST`, `SQL_QUERY`, `LLM_CALL`, `MCP_TOOL_CALL`, `SCRIPT`. |

## Consequences

- Retries, `onError`, timeouts, pause/skip valves, checkpoints and status keep working unchanged because the `RemoteProcessor` stub sits where `processor.process` sits. A lost worker is a retryable error and the next attempt may use another worker.
- The in-memory control state (tracker, scheduler slots, signal waits) stays the single point of failure. Control-plane HA (PostgreSQL lease, standby refuses starts, takeover recovers and resumes) is built; open: workers finding the new leader on their own, queued starts and waits surviving.
- A big-payload node turns the control plane into a data hop; the claim-check reference is needed before such nodes are placed.
- RSocket adds a dependency and native-image reachability work.
- Open: payload limit before claim-check is required; UI list of workers and per-worker load; `max-concurrent` counting remote slots.
