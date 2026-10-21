# Workers: assessment

How the `workers` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/workers.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Aworkers), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Aworkers) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Aworkers) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

1. **Today.** Placement by pool, free-slot choice, leases, drain, retry on another worker, optional mutual TLS, failover across several addresses. In-memory registry; plain TCP and one token by default.
2. **Make the link trustworthy.** Require TLS off loopback, per-worker credentials mapped to ids, and a decision on where secrets are resolved. Risks: `plainTokenDefault`, `sharedTokenIdentity`.
3. **Failover that works in the bad case.** Check leadership on every lease, reset the reconnect pause, close stale connections. Risks: `deposedKeepsWorkers`, `reconnectBackoff`, `staleConnections`.
4. **Make a placed node behave like a local one.** Map errors back, forward the log sink and trace context, count budgets on the control plane, enforce capacity, mark stateful plugins. Risks: `abortLostAcrossWire`, `liveLogsLost`, `noTraceContext`, `budgetPerJvm`, `capacityRace`, `statefulPlacement`, `perAttemptInit`.
5. **Handle data and upgrades.** A payload limit with a reference above it, a protocol version and a compatibility test. Risks: `payloadAsJson`, `wireVersion`.
6. **Verify the native path.** A native registration test in CI and a clear failure when the role switch is missing. Risks: `nativeUnverified`.

## Quality lenses

### Scalability

Scales the plugin call out. The control plane still carries every message in both directions and holds all state, so it is the ceiling.

**Ceilings today**

- The control plane is a data hop for every message
- One RSocket connection per worker
- The registry is an in-memory map scanned on each pick
- No size limit or claim check for large payloads

**Levers today**

- Pools, plugin types and free-slot choice
- Back-pressure and cancel end to end
- Workers need no inbound port

**Needs design work**

- A payload limit and a reference for large bodies
- Atomic slot accounting or worker-side limits
- Autoscaling signals from the slot gauge

### Performance

Each attempt adds a round trip and a JSON conversion of every message in both directions, and an initialise and prepare on the worker.

**Ceilings today**

- JSON conversion of every message twice
- Initialise and prepare per attempt (per message with per-message policies)
- Registry list sorted per pick

**Levers today**

- Streaming rather than request-reply per message
- One channel for all messages of an attempt (without per-message policies)

**Needs design work**

- A benchmark of latency and throughput per message
- Reuse of the prepared plugin across attempts on a worker

### Availability

A dead worker is a delay, not a failed run. The weak points are the control plane, which is the single owner of state, and the time workers take to find a new leader.

**Ceilings today**

- A deposed but running control plane keeps its workers
- The reconnect pause grows to 30 s and is not reset
- Attempts in flight at a failover fail and retry
- No queue: no worker means an immediate failure

**Levers today**

- Lease expiry
- Retry on another worker, tested with SIGKILL
- Graceful drain
- Several control-plane addresses

**Needs design work**

- Check leadership on every lease
- Reset the backoff after a healthy connection
- An option to wait for a worker instead of failing

### Security

The weakest lens. Everything that makes the module safe is optional or shared: plain TCP by default, one token for all workers, and node configs and payloads on the wire.

**Ceilings today**

- Plain TCP by default
- One shared token and self-declared worker ids
- Node config (with any literal secret) and payloads cross the wire
- A control plane can send any START to a worker for an allowed type

**Levers today**

- Constant-time token comparison
- Mutual TLS with host-name verification (tested)
- Per-worker allow-list of plugin types
- Processors only

**Needs design work**

- Per-worker credentials or certificates mapped to ids
- TLS required by default when a non-loopback address is used
- A decision on where secrets are resolved (design question 3)

### Operability

One list endpoint and two gauges. Worker-side logs, meters and spans stay on the worker.

**Ceilings today**

- Plugin meters and logs live in the worker's JVM
- No live log stream from placed nodes
- Warnings for every reconnect attempt
- No UI list of workers

**Levers today**

- GET /api/workers
- yukta.workers and yukta.worker.slots gauges
- Log lines for registration, refusal and worker gone

**Needs design work**

- Forward the log sink and trace context
- Per-worker load and failure counters
- A readiness check for 'at least one worker in pool X'

### Evolvability

Two small records are the whole protocol, which is easy to change and easy to break.

**Ceilings today**

- No version on the frames
- No SIGNAL frame, so stateful and signal plugins cannot be placed
- Control plane and worker must be the same build

**Levers today**

- A small, clear frame set
- Real-connection tests
- Role chosen by properties

**Needs design work**

- A version in REGISTER and a compatibility rule
- Frames for signals, trace context and deadlines as designed
- A test that mixes two versions

## Questions to settle

- **Security.** A device on your network can reach the control plane's RSocket port. List what it can learn and what it can make a worker do. (risk `plainTokenDefault`)
- **Identity.** One worker is compromised. How do you cut it off without restarting the others? (risk `sharedTokenIdentity`)
- **Availability.** The leader loses its database lease but keeps running. Where do the workers end up and when do placed nodes work again? (risk `deposedKeepsWorkers`)
- **Availability.** A control plane has restarted five times this week. What is the pause before a worker reconnects after the sixth? (risk `reconnectBackoff`)
- **Correctness.** A placed LLM_CALL node hits a budget with hardStop. What does the run do, and what does it do for a local node? (risk `abortLostAcrossWire`)
- **Governance.** A run uses three workers and has a token budget of 100,000. How much can it spend? (risk `budgetPerJvm`)
- **Operability.** Someone asks to watch a placed build step live. What do they see? (risk `liveLogsLost`)
- **Scale.** A placed node returns a 200 MiB file. Which components hold it, and how many times? (risk `payloadAsJson`)
- **Evolution.** You change a field of the message record. In which order do you upgrade the control plane and the workers, and what breaks in between? (risk `wireVersion`)
- **Design.** Which of your plugins keep anything between messages, and is it safe to place them? (risk `statefulPlacement`)

## Last risk review

Risk ids are descriptive names. No backlog identifiers apply: this is the first guide for the module, written on 2026-10-15 from the code of main and the design in [remote workers plan](../plans/remote-workers.md). Items worded 'looks' or 'by Reactor's rules' come from reading the code, not from a measurement.
