# Supervisor framework: threat-model review (S10, #2159)

Reviewed on `main` against the security table of `design/plans/supervisor-framework.md` (2026-11-12). One result per row; each names the control in the code and the test that fails if it breaks. Result: no open high finding.

| Threat | Result | Control in code | Proof |
|---|---|---|---|
| A stolen human key stops runs | Held | `ControlReviewer` finds the one owner; `operatorAccess` is none unless listed (`409 CONTROLLED_BY_SUPERVISOR`) | `ControlReviewerOwnerTest`, `SupervisedControlTest` (table over the inventory) |
| An external program acts with a person's rights | Held | `AgentCredentialFilter` accepts a supervisor credential only on `/api/agent/v1/**`; `ApiKeyFilter` leaves the path to it; actor kind `SUPERVISOR` | `AgentCredentialFilterTest`, `AgentApiControllerTest`, `AgentMcpControllerTest` |
| A credential leaks | Held | `AgentCredentials`: SHA-256 hash, shown once, expiry, address list; revoke or disable cuts open streams (`AgentHub.credentialChanges`) | `AgentCredentialsTest`, `AgentApiControllerTest.revokedCredentialEndsTheOpenStream`, `.disablingTheSupervisorEndsTheStreamAndRefusesTheNextCall` |
| An engine acts outside what its supervisor owns | Held | `DirectiveGate` and `SupervisorHost` check ownership from the real target; a lookup error is not owned | `DirectiveGateTest`, `SupervisorHostDirectivesTest`, `SupervisorHostScopeEnforcementTest` |
| Two supervisors fight | Held | One-owner rule at every save (`OwnershipValidator`, `DefinitionChecks`); arbiter removed | `OwnershipValidatorTest`, `SupervisorServiceTest` |
| An administrator removes the protecting supervisor | Held | `locked` assignments change only with `unlock` by a key in `yukta.supervisor.lock-holders`; changes audited under run id `supervisors` | `SupervisorAssignmentsTest` |
| Data leaks through the read API | Held | `SupervisorQueries.readsFor(scope)`: owned units, paged, at most 200, node outputs off unless `readNodeOutputs`; not owned reads as not found | `MergedReadsTest`, `AgentApiControllerTest.whatIsNotOwnedOrNotReadableIsNotFound` |
| An engine overloads the server | Held | Per-supervisor rate limit, `SseStreams` per caller, `MAX_OPEN_REVIEWS`, bounded buffers with `EventsMissed` | `AgentHubTest` (`readerThatFallsBehind...`, `onlyTheNewestSignalsAreRemembered`), `SupervisorHostTest` |
| A loop of supervisors | Held | A supervisor never sees its own outcomes; internal sessions hidden; delegation depth 4; `yukta.supervisor.max-depth` | `SupervisorHierarchyTest.chainOfFourIsFineAndFiveIsRefused`, `SupervisorHostInternalTest` |
| A new endpoint skips the checks | Held | `SupervisedControl` is the only entry; `ControlEntryRules` (ArchUnit); engines depend on `plugin-api` only | `ControlEntryRules`, `ModuleLayeringRules` in `architecture` |
| A reviewer that hangs or crashes | Held | Review and delegation timeouts; an error or silence refuses, a stop still passes; a late or repeated answer is 409 | `DelegationChainTest`, `AgentHubTest.anOpenReviewOrDelegationIsAnsweredOnceAndOnlyInTime`, `AgentApiControllerTest.lateVerdictIsRefused` |
| Replay and duplicates | Held | A directive key is applied once (newest 1000), `answers:<id>` once; starts have `Idempotency-Key` | `SupervisorHostDirectivesTest` |
| An engine acts without any trigger | Held | `initiative` allow-list (default none), a reason, `maxInitiativePerMinute`, dry run; a cause `event:<id>` must name a signal sent | `DirectiveGateTest`, `AgentApiControllerTest.causeNamingNoSignalSentIsRefused...` |
| Loss of independent vetoes (D10) | Accepted | The owner and its delegation chain are the only authority; an administrator can detach unless `locked` | decision D10 |

**Fuzz and chaos.** The directive body is fuzzed in `AgentApiControllerTest.garbageInDirectiveIsOnlyEverAnIllegalArgument` (300 random bodies: only a refusal, never a crash). Chaos: the engine closes with a review pending (the call fails with a conflict), the credential is revoked and the supervisor disabled while a stream is open (the stream ends, the next call is refused). All run in the normal `:web:test` and `:core:test` tasks.

**Emergency stop.** Decided in S0 and recorded in ADR 0002: an `ADMIN` emergency stop is not in v1. An administrator can detach any owner that is not `locked`, and a `locked` one needs a lock holder; both are audited. Revisit if a lock-out is seen.
