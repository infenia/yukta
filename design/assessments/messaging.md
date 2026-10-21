# Messaging: assessment

How the `messaging` module should evolve and where its quality ceilings are. Moved from the internal docs pages; the module itself is described in the [module guide](../../docs/contributing/modules/messaging.mdx).

Open work is tracked in GitHub issues, not here: [risks](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Arisk%20label%3Amessaging), [bugs](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20type%3ABug%20label%3Amessaging) and [everything open](https://github.com/infenia/yukta/issues?q=is%3Aissue%20is%3Aopen%20label%3Amessaging) for this module. Risk short names below (`openOps`, ...) are the risk issues' legacy ids.

## Evolution path

1. **Today.** One immutable envelope, one pass-through channel, core stamps history and routes by port. Several fields carried but unused.
2. **Decide the unused fields.** For each unenforced field choose: enforce it (for example an expiry check), or remove it. Make `from` and `withPayload` use one copy path. Risks: `unenforced`, `copyDrift`, `wideContract`.
3. **Define the channel contract.** Write what a channel may do on failure, how it signals slowness, and fix the registration through auto-configuration. Done: a skipped processor now goes through the channel too. Risks: `channelErrors`, `conditionalOrder`, `edgeDropsUnseen`.
4. **Payload model.** Choose a payload story: a canonical JSON tree, an optional schema, and a claim check for large bodies. Risks: `payloadSize`, `untypedPayload`, `shallowImmutable`.
5. **Better lineage.** Carry history through split and aggregate, record truncation, and add a per-output id if needed. Risks: `historyCap`, `lineageReset`, `idNotUnique`.
6. **A real second channel.** Build one durable or remote channel to prove the SPI, and trusted-header rules for it. Risks: `remoteSpi`, `forgeable`, `wireFormat`.

## Quality lenses

### Scalability

The module has no state, so it scales with whatever uses it. The question is the envelope's weight when many messages flow: each wither copies 22 fields and metadata is copied per header change. History is capped so copies do not grow with length.

**Ceilings today**

- Copy cost is proportional to metadata size at every header change
- Payload is carried by reference, so it is as large as the plugin makes it
- One provider for the whole server; no per-run channel state in the SPI
- In-process Flux only; no proof of a cross-machine transport

**Levers today**

- History cap of 64
- Singleton direct channel, no extra operators
- Builder to batch several changes into one copy

**Needs design work**

- A measured limit for payload and metadata size
- A defined remote or durable channel contract before scale-out
- Optionally a cheaper copy path for header-only changes

### Performance

Per message the default cost is small: a few record copies per node and one list append. The channel adds nothing. In our view the module is not a bottleneck; the cost shows up when payloads are large or headers are rewritten many times per node.

**Ceilings today**

- Each wither allocates a new 22-field record
- Each header change copies the whole metadata map (an unmodifiable copy is rebuilt)
- Constructor does null checks, a stream scan of metadata values and a list copy for every instance

**Levers today**

- Pass-through channel with identical stream objects
- Short-circuit when metadata has no nulls (copy only)
- Capped history keeps copies constant

**Needs design work**

- A micro-benchmark for the envelope under a realistic header load
- Guidance on when to use `toBuilder()`

### Availability

Barely applies: the module holds no state and does no I/O, so it cannot be down by itself. Its availability story is about what a replacement channel could do to the node's stream.

**Ceilings today**

- A faulty custom channel can fail every node in the run (its errors are outside the retry policy)
- Two competing provider beans stop the server from starting
- No acknowledgement or redelivery in the SPI

**Levers today**

- Default channel cannot fail
- Immutable messages make sharing safe in concurrent branches
- Constructor tests for odd inputs

**Needs design work**

- A documented failure contract for channels (retry, error port, or fail)
- A startup check that exactly one provider exists, with a clear message

### Security

No authentication, no network and no secrets here. The relevant points are trust in header values and what travels in a message. The module does not sign, validate or encrypt anything, and the envelope makes no secrecy promise.

**Ceilings today**

- Any plugin can set trace id, correlation id, workflow id, priority or control flag on a message it emits
- Core overwrites only the source node; other identity fields are unverified
- Payload and metadata may hold secrets; core echoes a truncated payload into run log lines
- A custom channel runs inside the JVM with full access to every message

**Levers today**

- Source node is set by core, not by plugins
- Unmodifiable copies protect the envelope from accidental changes
- Channel hook exists, so signing or encryption could be added in one place

**Needs design work**

- A rule on which headers plugins may set
- A redaction hook or marker for sensitive headers
- A review of what the control flag is meant to do

### Operability

Little to operate. The visible pieces are the history list, the trace id and the failure headers, which together explain most of a message's journey. There is no metric from this module.

**Ceilings today**

- No counters for dropped null headers or truncated history
- No indication that a custom channel is active (no log or metric from the default wiring)
- History truncation leaves no marker

**Levers today**

- Trace id and history in every message
- Failure fields and headers on dead letters
- Tests documenting each rule

**Needs design work**

- A startup log naming the active channel provider
- A header or flag that says history was truncated

### Evolvability

The envelope is the most widely shared type in the codebase. A new field touches the interface, the record, two hand-written copy paths and the tests, and then ripples through every module's rebuild. The channel SPI is small and easy to extend, but untested beyond a pass-through.

**Ceilings today**

- 40-method interface with one implementation
- Two copy paths list all 22 fields by hand (`from` and `withPayload`)
- Payload typed as a plain Object, so a schema change is invisible to the compiler
- Fields carried but unused tempt future code to rely on them

**Levers today**

- Record and Lombok withers cut the boilerplate
- Builder with `toBuilder`
- Tests for immutability and the cap
- A clear note in the interface for the history rule

**Needs design work**

- A single copy path (for example builder only) so a new field cannot be missed
- A decision to remove or enforce each unenforced field
- A typed or schema-checked payload option

## Questions to settle

- **Evolution.** Name one field of the envelope you would delete today, and one you would enforce. What breaks for each? (risk `unenforced`)
- **Scale.** If the SPI had to carry messages to another machine, which five questions must its contract answer first? (risk `remoteSpi`)
- **Correctness.** A plugin mutates a list inside its payload. Which other branches can see it, and when would anyone notice? (risk `shallowImmutable`)
- **Performance.** A message becomes 50 MiB. Which components copy it, and which only pass a reference? (risk `payloadSize`)
- **Security.** Which headers can a malicious plugin set that an operator would trust, and which should core re-stamp? (risk `forgeable`)
- **Operability.** You debug a message that went through 80 nodes. What can you still see in its history, and what can you rebuild from elsewhere? (risk `historyCap`)
- **Failure.** A custom channel throws on one message. What does the user see, and which component should decide? (risk `channelErrors`)
- **Design.** After a split, is each child the same message or a new one? What should its history say? (risk `lineageReset`)
- **Evolvability.** Is the 40-method interface worth keeping if only one implementation will ever exist? (risk `wideContract`)
- **Operability.** How would you know which channel provider is active in production, and how would two providers be diagnosed? (risk `conditionalOrder`)

## Last risk review

Risk ids are descriptive names. Old review items M1 to M13 for this module are all fixed and are not listed. Review of 2026-10-15: the module code is unchanged since the first guide; one risk was closed (a skipped processor now goes through the channel) and two were added (the envelope as a worker wire format, and edge-buffer drops that a channel never sees). No backlog identifiers apply to the remaining items.
