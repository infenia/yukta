# Persistence PostgreSQL: assessment

How the `persistence-postgres` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/persistence-postgres.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Apersistence-postgres), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Apersistence-postgres) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Apersistence-postgres) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

1. **Today.** Nine tables, seven stores, a lease. Statements are single and safe; recovery rewrites and multi-step operations are not atomic; the fencing number is unused.
2. **Make recovery safe.** Transactional rewrite of the log, durable start and end events, transactions for the multi-step operations. Risks: `logReplaceAll`, `logDropsEvents`, `noTransactions`.
3. **Fence the leader.** Pass the fencing token to the writes that matter and refuse older tokens in the statement. Risks: `fencingUnused`.
4. **Index and bound.** Index the event type and update time, stream the reads, make the KV cap and the clean-up honest, add a session delete. Risks: `compactionCost`, `readsWholeSets`, `historyTtlScan`, `kvCapRace`, `noDelete`, `noRetention`, `sessionConfigGaps`.
5. **Observe it.** Meters and a health check for the stores, a startup summary of which store is used, honest counters. Risks: `noStoreMetrics`, `halfDurable`, `heldBytesDrift`, `blockingCalls`.
6. **Operate it.** Separate accounts, an upgrade test on data, a documented backup and restore. Risks: `twoConnections`, `noMigrationPlan`.

## Quality lenses

### Scalability

Fine for one control plane and a few hundred runs. The log has one writer thread, the lease is one row, and several operations load or scan whole tables.

**Ceilings today**

- One writer thread and one statement at a time for the execution log
- Compaction and recovery scan the whole log
- Reads of the log and of a node's output load everything into memory
- Per-session history and KV counts scan their tables

**Levers today**

- Batched inserts of up to 100 events
- Large messages are stored out of line by PostgreSQL
- Trim to the newest runs, never open ones

**Needs design work**

- Indexes for the ttl delete and the compaction query
- Stream reads (a cursor) instead of loading whole sets
- Partition or archive the log if runs are many

### Performance

Single statements are cheap. The costly parts are the one-by-one rewrite at recovery, blocking reads on the resume path, and queries that count or group whole tables.

**Ceilings today**

- replaceAll inserts events one per round trip
- isFinished and isComplete are one query each, per node, blocking
- getAllConfigs runs about seven queries for a session
- get reads every row of a node to return the last

**Levers today**

- Batch insert for the log
- Upserts in one statement for counters and history
- Timeouts on every blocking wait

**Needs design work**

- Batch or bulk the recovery rewrite
- Fetch only the last checkpoint row
- A benchmark for log append rate and resume time

### Availability

A crash is recoverable and a standby can take over, but the pieces that make this safe are the least protected: the rewrite is not atomic and the fencing number is unused.

**Ceilings today**

- The log can lose queued events with the process
- A crash inside replaceAll loses the log
- A paused leader can still write after a takeover
- The database is a single point of failure (no failover logic here)

**Levers today**

- Lease with an expiry from the database clock
- Local validity measured from the time of sending
- Release of the lease on graceful stop
- Takeover actions isolated from each other

**Needs design work**

- A transactional rewrite of the log
- Fencing checks on writes by the old leader
- Durable acknowledgement for the run-end event

### Security

No authentication of its own: it trusts the database account it is given. The data it stores can include payloads, definitions with literal secrets and chat history.

**Ceilings today**

- Definitions, payloads and chat turns are stored as plain JSON
- One database account for the application; Flyway may use the same
- No row-level separation between sessions
- Secrets typed into a workflow config are stored as written

**Levers today**

- Values are always bound as parameters; the few column names that are concatenated into a statement come from constants in the code
- A pinned, patched scram-client
- Constant statement timeouts

**Needs design work**

- Separate migration and runtime accounts, documented
- Encryption at rest and in transit settings in the setup text
- Redaction of secret-like config before storage (see web and plugins)

### Operability

Little visibility: one gauge for the leader and nothing for the stores themselves.

**Ceilings today**

- No metrics for append rate, queue depth, dropped events, statement time or lock waits
- No health indicator for the stores
- The checkpoint byte counter drifts and restarts at zero
- No tool to inspect or repair a damaged log

**Levers today**

- The yukta.leader gauge
- Log lines for dropped events, failed batches and failed trims
- Spring Boot's own R2DBC and Flyway integration

**Needs design work**

- Meters for queue depth, drops and write latency
- A health check that runs a trivial query
- A documented backup and restore procedure

### Evolvability

Easy to add a store (one class, one migration, one bean). Hard parts are changing a stored JSON shape and moving a deployment to a newer schema while it holds data.

**Ceilings today**

- Forward-only migrations
- Stored JSON shapes are the Java records; a rename changes what old rows mean
- Tests start from an empty schema

**Levers today**

- Small classes, one per interface
- JSONB keeps most changes out of migrations
- Auto-configuration with one switch per store

**Needs design work**

- A test that migrates a database holding V1 data to the latest version
- A version field inside stored documents
- A rule for removing a column or table

## Questions to settle

- **Failure.** The server dies between the delete and the last insert of replaceAll. What can an operator still do, and what would they have needed? (risk `logReplaceAll`)
- **Reliability.** The log queue is full for ten seconds. Which events are lost, and which of them matter most? (risk `logDropsEvents`)
- **Availability.** A leader freezes for 40 seconds with a 15 second lease. Walk through what each server does when the leader wakes up. (risk `fencingUnused`)
- **Correctness.** An operator applies a session with twelve workflows and the database restarts after the sixth. What does the session look like? (risk `noTransactions`)
- **Performance.** A run has 400 nodes and the database answers in 50 ms. What does a resume cost in waiting time? (risk `blockingCalls`)
- **Scale.** You keep 500 runs of 5,000 events. What happens on each trim, and what happens to the queue meanwhile? (risk `compactionCost`)
- **Operations.** How would you tell, from the running system, which kinds of state are durable? (risk `halfDurable`)
- **Privacy.** A customer asks you to delete everything about one session. What do you run? (risk `noDelete`)
- **Evolution.** You rename a field of the workflow definition record. What happens to the stored definitions? (risk `noMigrationPlan`)
- **Security.** Which database account does the application use, and what can it do that it should not? (risk `twoConnections`)

## Last risk review

Risk ids are descriptive names. No backlog identifiers apply: this is the first guide for the module, written on 2026-10-15 from the code of main. Items worded 'looks' or 'can' are judgement from reading the statements, not measured defects.
