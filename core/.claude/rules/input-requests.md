---
paths:
  - "src/**/service/input/**"
  - "src/**/service/redact/**"
---

# Input requests (a plugin asks, the broker waits)

Reference for `InputBroker`, `InputRequestStore` and friends. Epic #1787; contracts in `plugin-api` (`com.infenia.yukta.plugin.input`), decisions in `design/decisions/0001-input-requests.md`.

- **Gateway**: `DefaultInputGateway` is a singleton bean; a plugin takes `InputGateway` through an `ObjectProvider`. `ask` reads run and node from the Reactor context (`InputIdentity.from`), so the engine binds identity and a plugin never passes an execution or node id.
- **Broker**: `ask` (resume from `findAnsweredByKey`, join an open request with the same key, caps, redact, `createIfAbsent`), `answer`, `escalate`, `cancel`, `cancelExecution`, `sweep`. Every change is `transition`: read, check the allowed state and version, `compareAndSet`, re-read on a lost race. A losing caller gets the current request back, so calls are idempotent. Terminal states never change. `answer` stores the response after winning the compare-and-set, then completes the waiters.
- **Waiting**: one `Sinks.One` per request id; callers subscribe to it. When the last waiting caller cancels, the request is cancelled (`node-cancelled`). The orchestrator cleanup calls `cancelExecution(executionId, "run-ended")` through an `ObjectProvider<InputBroker>` (setter, so the constructor and its tests do not change).
- **Deadlines**: absolute `deadline` on the request; `InputDeadlineSweeper` (`SmartLifecycle`) calls `sweep()` every `yukta.input.sweep-interval` only while `LeaderElection.isLeader()`. `FAIL` and `ESCALATE` (until phase 6) expire the request; `USE_DEFAULT` answers it as `Responder.SYSTEM`.
- **Limits** (`yukta.input.*`, `YuktaProperties.Input`, validated at start-up): open per execution and per session, `max-ttl` (a longer ttl is **refused**, rule `ttl`), `memory-max` finished requests in `InMemoryInputRequestStore`. A refusal is an `InputLimitException` and `yukta.input.denied{rule}`. The checks run before `createIfAbsent`, so a parallel burst can pass them by a few.
- **Redaction**: `InputRedactor` (prompt, context, escalation reason and advice) on top of `SecretRedactor`, the one list of secret patterns and key names (`LogSanitizer` in `mcp` uses it too). It also replaces `${env.X}` references and `yukta.input.redact-keys`. Events, the store and the log only ever see redacted text; the log never carries the prompt or an answer text.
- **Events and log**: `InputRequested`, `InputEscalated`, `InputAnswered`, `InputExpired`, `InputCancelled` on `ExecutionEventFeed`; one `ExecutionLogEvent.Type.INPUT` per change, payload `event` = `INPUT_REQUESTED` ... `INPUT_CANCELLED`. Code that reads the log by type ignores `INPUT` (tested in `ExecutionRecovery`, `LogRetention`, `RunAnalytics`, `StartIdempotency`).
- **Metrics**: `yukta.input.requests{kind,risk,outcome}` (asked, answered, defaulted, escalated, expired, cancelled), `yukta.input.wait{tier}`, `yukta.input.open`, `yukta.input.denied{rule}`.
- **Store contract**: `InputRequestStoreContract` in `plus/src/testFixtures/.../testkit`; the in-memory store runs against it from `plus` `ContractsTest`. Core keeps the broker tests (`InputBrokerTest`, virtual time and a mutable clock, no sleeping).
