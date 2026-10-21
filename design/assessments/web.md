# Web: assessment

How the `web` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/web.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Aweb), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Aweb) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Aweb) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

A suggested order, from cheap and safe to large. Each step names the risks it addresses.

1. **Today.** 16 controllers, four filters, one error handler. Loopback bind with a start-up guard, named keys with roles and session limits. Streams with ids and a heartbeat. Cursor paging over full lists. Run search, control audit and version endpoints added. Tests for every controller, mapper and filter.
2. **Close the doors.** Done: fail closed when the address is open and no key is set (but see the MCP-key case), guard the actuator. Next: guard or drop the docs, let the UI send the key, log and count rejected requests with a request id and the key name. Risks: `openByDefault`, `uiNoKey`, `silent401`.
3. **Make the contract honest.** Add a 409 for conflicts; stop the three local catches; validate and record limit changes; unify the edge-port default; apply ownership checks on the canonical routes and add a session filter to the events stream. Risks: `noConflict`, `localCatch`, `limitsEndpoint`, `mapperDrift`, `ownershipGap`.
4. **Scale reads and streams.** Page in the store; give log lines durable sequence numbers; tell slow readers they missed events; cap streams, rate and body size. Risks: `pagingLinear`, `logSeam`, `slowReader`, `noLimits`, `searchScan`.
5. **Identity and operations.** Done: named keys, roles, session limits, hashed keys. Next: expiry and revocation without a restart, the key name in the access log, a per-key rate limit, or an identity provider; masked secrets in read APIs. Risks: `staticKeys`, `configUnredacted`.
6. **Retire aliases and dead code.** Move the CLI and UI to canonical routes, remove the 14 aliases, delete the unused records. Risks: `legacyAliases`, `deadDtos`.

## Quality lenses

### Scalability

Web keeps no state, so more instances would be easy in principle, but core holds the run state in one process and nothing here is shared. The non-blocking server handles many connections cheaply; the cost is in lists loaded in full and in streams with no cap.

**Ceilings today**

- One core process behind all of it
- History and session lists are loaded in full then sliced
- Every open stream holds a subscription and, for logs, a 1024-line buffer, with no cap
- No rate limit on any route

**Levers today**

- Non-blocking WebFlux and virtual threads
- Page size capped at 1000
- Summaries load only the requested page

**Needs design work**

- Page in the store (core), not by slicing a full list
- Limits on connections and request rate
- A multi-instance story for streams if core ever scales out

### Performance

Per-request work is small: bind, one core call, wrap. The risks are list endpoints that scale with data size and heavy streams.

**Ceilings today**

- Page cost grows with history length (full load plus linear cursor search)
- Session summaries read configs one after another
- Log streams copy up to 1024 buffered lines per connection

**Levers today**

- Compile-time mappers (no reflection)
- Summaries read only one page of configs
- Heartbeats avoid reconnect storms behind proxies

**Needs design work**

- Store-level paging by cursor
- Parallel or batched summary reads
- Load numbers for the stream endpoints (perf/ scripts exist at the repository root)

### Availability

Web has no state of its own to lose. Its availability is core's: if core is down or restarting every stream drops and clients must reconnect.

**Ceilings today**

- Streams drop on restart; ids are not durable
- Slow readers miss live events
- No timeout or lifetime for streams

**Levers today**

- Heartbeat comments keep idle streams open through proxies
- Last-Event-ID resume
- 500s carry a reference id for support

**Needs design work**

- Documented reconnect behaviour for clients
- Health of the stream path under load
- Graceful stream close on shutdown

### Security

Much improved since the first guide: named keys with roles, session-limited keys, hashed keys and rotation, a guarded actuator, path normalisation, host and origin checks, and a start-up guard. What is left is mostly operational: static keys, open docs, and no rate limits.

**Ceilings today**

- Keys are static configuration; no expiry or revocation without a restart
- Open on loopback when no key is set (HostOriginFilter stops browsers only)
- Swagger UI and OpenAPI are outside the key
- Node config returned unredacted; 4xx echoes core messages

**Levers today**

- Loopback bind by default and a start-up guard for other addresses
- Roles, named and session-limited keys, hashed keys, several keys for rotation
- Path normalisation (400 on ambiguous paths) and Host/Origin checks
- Strict JSON and field validation
- 500s carry no exception text; request ids validated

**Needs design work**

- Rate limits and a stream cap
- Redaction of secrets in read APIs
- The UI able to send the key
- Log or count rejected requests, with the key name

### Operability

Good basics: a request id on every accepted call, one log line per request and uniform errors. Gaps: rejected calls (400, 401, 403) are invisible and the access log carries no caller.

**Ceilings today**

- 401s have no id and no log line
- No caller identity in logs
- No request metrics owned by this module

**Levers today**

- Request id in headers and every log line
- Access log with status and duration
- Uniform error envelope
- OpenAPI and Swagger UI

**Needs design work**

- Log or count rejected requests
- Metrics for streams and 4xx/5xx rates
- Caller identity once it exists

### Evolvability

Small, consistent and well tested, but with leftovers: deprecated aliases still in use, response types that nothing uses, and two mappers that disagree on a default.

**Ceilings today**

- 14 aliases that clients still call
- 11 unused response records
- Mapper duplication
- Contract is the OpenAPI plus the UI's generated types

**Levers today**

- Controller conventions and helpers
- Tests for every controller, mapper and filter
- Records and MapStruct keep DTOs short

**Needs design work**

- Alias retirement plan
- Remove or move unused records
- A contract test that fails on breaking OpenAPI changes

## Questions to settle

- **Security.** Roles now separate reading, running and authoring. Which key should be allowed to submit a workflow that can run a program, and should an OPERATOR key be able to? (risk `staticKeys`)
- **Security.** A container is started from the published image with no configuration. What stops a stranger on the same network from running code, and is that enough? (risk `openByDefault`)
- **Security.** Session ids look like tenant boundaries but the checks only run on some routes. What should a session mean to security? (risk `ownershipGap`)
- **Reliability.** A UI shows 'running' while the run actually finished, because the event was dropped for a slow tab. Where should the truth live? (risk `slowReader`)
- **Correctness.** Two log lines with identical text arrive within the same millisecond around a connect. Which one should a viewer see, and how would you prove the stream correct? (risk `logSeam`)
- **Performance.** A session has 200,000 runs of history. What does the 200th page of 500 cost, and what would you change first? (risk `pagingLinear`)
- **Operability.** On-call sees a spike of 500s. How would you tell conflicts from real bugs with today's mapping? (risk `noConflict`)
- **Governance.** Limit changes are now admin-only, audited with the actor and restored on restart. Should there be a floor, so that one call cannot set 'no limit'? (risk `limitsEndpoint`)
- **Evolvability.** The CLI uses the old routes and the UI the new ones. How do you migrate without breaking either, and what do you lose if the old routes carry the only ownership check? (risk `legacyAliases`)
- **Availability.** A client opens a thousand log streams and never reads them. What happens to memory, and what should the server do? (risk `noLimits`)

## Last risk review

Risk ids are short names. Impact (i) and effort (e) are our own 1 to 5 judgement. No backlog ids are used because the earlier review findings (W1 to W17) are all fixed. Review of 2026-10-15 against main (after PR #373): 1 risk closed (sharedKey, replaced by the smaller staticKeys), 5 partly closed (openByDefault, ownershipGap, noConflict, limitsEndpoint, noLimits), 2 added (staticKeys, searchScan); the others are unchanged.
