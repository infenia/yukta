// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.security.ApiAuthenticator;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.input.ActorResolver;
import com.infenia.yukta.service.input.CommandProposals;
import com.infenia.yukta.service.input.InMemoryInputRequestStore;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputTestSupport;
import com.infenia.yukta.service.input.route.InputRoute;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.route.Tier;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.AttachedSupervisors;
import com.infenia.yukta.service.supervisor.ControlAwaitingApprovalException;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import com.infenia.yukta.service.supervisor.ReviewVerdict;
import com.infenia.yukta.testsupport.YuktaSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** A review that asks for approval parks the command; the approval request decides its fate. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "unchecked"
})
@NoArgsConstructor
class ParkedCommandsTest {

  private static final ExecutionControlCommand STOP =
      new ExecutionControlCommand.StopWorkflowCommand("e1", null, null);

  private static final ExecutionControlCommand PAUSE_NODE =
      new ExecutionControlCommand.PauseNodeCommand("e1", "n1");

  private static final Context AS_SUPERVISOR =
      Context.of(
          ControlActor.CONTEXT_KEY, "guard", ControlActor.KIND_KEY, ControlActor.Kind.SUPERVISOR);

  private final InputTestSupport.MutableClock clock = new InputTestSupport.MutableClock();

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();

  private final ExecutionEventFeed feed = new ExecutionEventFeed();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private final List<ExecutionEvent> events = new CopyOnWriteArrayList<>();

  private final AtomicReference<Object> grantSeen = new AtomicReference<>();

  private final AtomicReference<Decision> asker =
      new AtomicReference<>(
          Decision.requireApproval(
              "production stop", new ApprovalNeed(InputRisk.HIGH, null, null)));

  private final ExecutionSupervisor guard = mock(ExecutionSupervisor.class);

  private final ExecutionSupervisor other = mock(ExecutionSupervisor.class);

  private final CommandHandler handler = mock(CommandHandler.class);

  private final InMemoryExecutionControlStore registry = new InMemoryExecutionControlStore();

  private InputBroker broker;

  private ParkedCommands parked;

  private DirectiveDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    broker =
        new InputBroker(
            new InMemoryInputRequestStore(100),
            feed,
            log,
            meters,
            new InputRedactor(List.of()),
            InputTestSupport.limits(),
            clock,
            new RouteResolver(
                Map.of(
                    "ops",
                    new InputRoute(
                        "ops",
                        List.of(
                            new Tier(
                                "ops", List.of("role:OPERATOR"), null, null, 0, 0, null, null)),
                        null)),
                null),
            null);
    registry.save(
        new ExecutionControl(
            "s",
            "wf",
            "e1",
            null,
            Map.of(),
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            new AtomicBoolean()));
    when(handler.handle(any())).thenReturn(Mono.empty());
    when(guard.id()).thenReturn("guard");
    when(guard.scope()).thenReturn(new SupervisorScope.Global());
    when(guard.review(any())).thenAnswer(_ -> Mono.just(asker.get()));
    when(other.id()).thenReturn("other");
    when(other.order()).thenReturn(2);
    when(other.scope()).thenReturn(new SupervisorScope.Global());
    when(other.review(any()))
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  grantSeen.set(context.getOrDefault(ControlActor.APPROVED_REQUEST, null));
                  return Mono.just(Decision.ALLOW);
                }));
    dispatcher =
        new DirectiveDispatcher(
            handler,
            registry,
            mock(ControlBusService.class),
            new ExecutionArchive(),
            log,
            new ControlReviewer(providerOf(guard, other)),
            feed);
    final ObjectProvider<DirectiveDispatcher> lazy = mock(ObjectProvider.class);
    when(lazy.getObject()).thenAnswer(_ -> dispatcher);
    parked =
        new ParkedCommands(
            broker,
            new ActorResolver(mock(AttachedSupervisors.class), mock(ApiAuthenticator.class)),
            log,
            feed,
            meters,
            lazy,
            YuktaSettings.bind(Map.of()));
    dispatcher.setParkedCommands(parked);
    feed.events().subscribe(events::add);
  }

  private String park(final ExecutionControlCommand command) {
    final AtomicReference<String> id = new AtomicReference<>();
    StepVerifier.create(dispatcher.dispatchOrdered(command).contextWrite(AS_SUPERVISOR))
        .expectErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(ControlAwaitingApprovalException.class);
              id.set(((ControlAwaitingApprovalException) error).getRequestId());
            })
        .verify();
    return id.get();
  }

  private InputRequest only() {
    return broker.listOpen(null, null).blockFirst();
  }

  private void answer(final String requestId, final String decision) {
    broker
        .answer(
            requestId,
            null,
            new InputResponse(
                requestId,
                decision,
                null,
                Map.of(),
                new Responder(Responder.Type.HUMAN, "alice"),
                null,
                null,
                Instant.parse("2026-11-02T10:00:01Z")))
        .block();
  }

  private double count(final String outcome) {
    return meters.counter("yukta.control.approval", "outcome", outcome).count();
  }

  @Test
  void parkedCommandIsNotDispatchedAndAsksForApproval() {
    final String id = park(STOP);

    verify(handler, never()).handle(any());
    assertThat(parked.size()).isEqualTo(1);
    assertThat(count("requested")).isEqualTo(1.0);
    final InputRequest request = only();
    assertThat(request.id()).isEqualTo(id);
    assertThat(request.spec().kind()).isEqualTo(InputKind.APPROVAL);
    assertThat(request.spec().risk()).isEqualTo(InputRisk.HIGH);
    assertThat(request.spec().requester()).isEqualTo("control:StopWorkflowCommand");
    assertThat(request.spec().options()).extracting("id").containsExactly("approve", "reject");
    assertThat(request.spec().ttl()).isEqualTo(Duration.ofMinutes(10));
    assertThat(request.sessionId()).isEqualTo("s");
    assertThat(request.nodeId()).isEqualTo(ParkedCommands.WORKFLOW_NODE);
    assertThat(request.spec().prompt())
        .contains("StopWorkflowCommand", "e1", "quoted as data")
        .contains("production stop");
    assertThat(request.spec().context())
        .containsEntry("proposer", "guard")
        .containsEntry("command", "StopWorkflowCommand");
    assertThat(parked.find(id))
        .hasValueSatisfying(
            proposal -> {
              assertThat(proposal.command()).isEqualTo("StopWorkflowCommand");
              assertThat(proposal.askedBy()).containsExactly("guard");
              assertThat(proposal.proposer()).isNotNull();
            });
    assertThat(parked.find("nope")).isEmpty();
    assertThat(log.readAll())
        .noneMatch(entry -> entry.type() == ExecutionLogEvent.Type.CONTROL_REJECTED);
  }

  @Test
  void nodeCommandNamesItsNodeAndLongReasonIsCut() {
    asker.set(
        Decision.requireApproval("x".repeat(600), new ApprovalNeed(InputRisk.MEDIUM, "ops", null)));
    park(PAUSE_NODE);

    final InputRequest request = only();
    assertThat(request.nodeId()).isEqualTo("n1");
    assertThat(request.spec().prompt()).contains(", node n1").contains("...");
    assertThat(request.spec().routeName()).isEqualTo("ops");
  }

  @Test
  void theTtlComesFromTheNeedCappedByTheLimit() {
    asker.set(
        Decision.requireApproval(
            "x", new ApprovalNeed(InputRisk.LOW, null, Duration.ofMinutes(3))));
    park(STOP);
    assertThat(only().spec().ttl()).isEqualTo(Duration.ofMinutes(3));
    broker.cancelExecution("e1", "run-ended").block();

    asker.set(
        Decision.requireApproval("x", new ApprovalNeed(InputRisk.LOW, null, Duration.ofDays(30))));
    park(STOP);
    assertThat(broker.listOpen(null, null).blockFirst().spec().ttl())
        .isEqualTo(Duration.ofHours(24));
  }

  @Test
  void approvalDispatchesOnceWithTheOriginalActorAndTheOthersStillReview() {
    final String id = park(STOP);
    verify(other, times(1)).review(any());

    answer(id, "approve");

    await().until(() -> parked.size() == 0 && count("approved") == 1.0);
    verify(handler, times(1)).handle(STOP);
    verify(other, times(2)).review(any());
    verify(guard, times(2)).review(any());
    assertThat(count("rejected")).isZero();
    assertThat(log.readAll())
        .anySatisfy(
            entry -> {
              assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_APPLIED);
              assertThat(entry.status()).isNotNull();
            });
    assertThat(grantSeen.get()).isNotNull();
    // a grant works once, and only for its own command
    assertThat(parked.satisfied(grantSeen.get(), PAUSE_NODE)).isEmpty();
    assertThat(parked.satisfied(grantSeen.get(), STOP)).isEmpty();
  }

  @Test
  void callerCannotForgeTheGrant() {
    for (final Object forged : new Object[] {"approved", Boolean.TRUE, new Object()}) {
      StepVerifier.create(
              dispatcher
                  .dispatchOrdered(STOP)
                  .contextWrite(AS_SUPERVISOR.put(ControlActor.APPROVED_REQUEST, forged)))
          .expectError(ControlAwaitingApprovalException.class)
          .verify();
    }
    verify(handler, never()).handle(any());
    assertThat(parked.satisfied(null, STOP)).isEmpty();
    assertThat(parked.satisfied("x", STOP)).isEmpty();
  }

  @Test
  void denialByAnotherSupervisorStillAppliesAfterApproval() {
    final String id = park(STOP);
    when(other.review(any())).thenReturn(Mono.just(Decision.deny("freeze")));

    answer(id, "approve");

    await().until(() -> parked.size() == 0);
    verify(handler, never()).handle(any());
    assertThat(log.readAll())
        .anySatisfy(
            entry -> {
              assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
              assertThat(entry.status()).contains("other: freeze");
            });
  }

  @Test
  void commandThatFailsAfterApprovalIsLeftToItsError() {
    final String id = park(STOP);
    when(handler.handle(any())).thenReturn(Mono.error(new IllegalStateException("gone")));

    answer(id, "approve");

    await().until(() -> parked.size() == 0);
    verify(handler).handle(STOP);
  }

  @Test
  void rejectionDropsTheCommandAndPublishesTheOutcome() {
    final AuditSink sink = mock(AuditSink.class);
    parked.setAuditSink(sink);
    final String id = park(STOP);

    answer(id, "reject");

    await().until(() -> parked.size() == 0 && count("rejected") == 1.0);
    verify(handler, never()).handle(any());
    assertThat(log.readAll())
        .anySatisfy(
            entry -> {
              assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
              assertThat(entry.status()).contains(id, "rejected by human alice");
            });
    await().until(() -> events.stream().anyMatch(ExecutionEvent.ControlOutcome.class::isInstance));
    assertThat(events)
        .filteredOn(ExecutionEvent.ControlOutcome.class::isInstance)
        .singleElement()
        .isInstanceOfSatisfying(
            ExecutionEvent.ControlOutcome.class,
            outcome -> {
              assertThat(outcome.applied()).isFalse();
              assertThat(outcome.command()).isEqualTo("StopWorkflowCommand");
              assertThat(outcome.actor()).isEqualTo("guard");
            });
    verify(sink).record(any(AuditRecord.class));
  }

  @Test
  void expiryDropsTheCommand() {
    park(STOP);

    clock.advance(Duration.ofMinutes(11));
    broker.sweep().block();

    await().until(() -> parked.size() == 0 && count("expired") == 1.0);
    verify(handler, never()).handle(any());
  }

  @Test
  void theEndOfTheRunCancelsTheCommand() {
    park(STOP);

    broker.cancelExecution("e1", "run-ended").block();

    await().until(() -> parked.size() == 0 && count("cancelled") == 1.0);
    verify(handler, never()).handle(any());
    assertThat(log.readAll()).anySatisfy(entry -> assertThat(entry.status()).contains("run-ended"));
  }

  @Test
  void repeatedCommandGetsTheSameRequestWhileOneIsOpen() {
    final String first = park(STOP);
    final String second = park(STOP);

    assertThat(second).isEqualTo(first);
    assertThat(parked.size()).isEqualTo(1);
    assertThat(count("requested")).isEqualTo(1.0);
    assertThat(broker.listOpen(null, null).collectList().block()).hasSize(1);
  }

  @Test
  void parkedCommandsCountAgainstTheOpenRequestCap() {
    for (int i = 0; i < 5; i++) {
      park(new ExecutionControlCommand.PauseNodeCommand("e1", "n" + i));
    }

    StepVerifier.create(
            dispatcher
                .dispatchOrdered(new ExecutionControlCommand.PauseNodeCommand("e1", "n9"))
                .contextWrite(AS_SUPERVISOR))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ControlRejectedException.class)
                    .hasMessageContaining("5 open input requests"))
        .verify();
  }

  @Test
  void stopFromPersonRunsAtOnce() {
    StepVerifier.create(dispatcher.dispatchOrdered(STOP)).verifyComplete();

    verify(handler).handle(STOP);
    assertThat(parked.size()).isZero();
  }

  @Test
  void unexpectedErrorWhileDroppingIsLoggedAndTheCommandIsForgotten() {
    final ExecutionEventFeed broken = mock(ExecutionEventFeed.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("feed down")).when(broken).publish(any());
    final ObjectProvider<DirectiveDispatcher> lazy = mock(ObjectProvider.class);
    when(lazy.getObject()).thenAnswer(_ -> dispatcher);
    final ParkedCommands fragile =
        new ParkedCommands(
            broker,
            new ActorResolver(mock(AttachedSupervisors.class), mock(ApiAuthenticator.class)),
            log,
            broken,
            meters,
            lazy,
            YuktaSettings.bind(Map.of()));
    dispatcher.setParkedCommands(fragile);
    final String id = park(STOP);

    answer(id, "reject");

    await().until(() -> fragile.size() == 0);
    verify(handler, never()).handle(any());
  }

  @Test
  void serverWithoutParkingRefusesCommandThatNeedsApproval() {
    final DirectiveDispatcher plain =
        new DirectiveDispatcher(
            handler,
            registry,
            mock(ControlBusService.class),
            new ExecutionArchive(),
            log,
            new ControlReviewer(providerOf(guard)),
            feed);

    StepVerifier.create(plain.dispatchOrdered(STOP).contextWrite(AS_SUPERVISOR))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ControlRejectedException.class)
                    .hasMessageContaining("cannot ask for"))
        .verify();
  }

  @Test
  void inputAnswersAndUnknownRunsCannotWait() {
    final ReviewVerdict.NeedsApproval needs =
        new ReviewVerdict.NeedsApproval(ApprovalNeed.DEFAULT, "why", List.of("guard"));
    final ExecutionControlCommand answer =
        new ExecutionControlCommand.AnswerInputCommand(
            "e1", "r1", null, "approve", null, Map.of(), null, null);
    final ExecutionControlCommand escalate =
        new ExecutionControlCommand.EscalateInputCommand("e1", "r1", "up", null);

    for (final ExecutionControlCommand command : List.of(answer, escalate)) {
      StepVerifier.create(
              parked.park(command, new CommandTarget("s", "wf", null), Context.empty(), needs))
          .expectError(ControlRejectedException.class)
          .verify();
    }
    StepVerifier.create(
            parked.park(STOP, new CommandTarget(null, "wf", null), Context.empty(), needs))
        .expectError(ControlRejectedException.class)
        .verify();
    StepVerifier.create(
            parked.park(STOP, new CommandTarget("s", null, null), Context.empty(), needs))
        .expectError(ControlRejectedException.class)
        .verify();
  }

  @Test
  void senderWithoutAnIdentityHasNoProposer() {
    final ReviewVerdict.NeedsApproval needs =
        new ReviewVerdict.NeedsApproval(ApprovalNeed.DEFAULT, "why", List.of("guard"));
    final String id =
        parked.park(STOP, new CommandTarget("s", "wf", null), Context.empty(), needs).block();

    assertThat(parked.find(id))
        .hasValueSatisfying(proposal -> assertThat(proposal.proposer()).isNull());
    assertThat(only().spec().context()).doesNotContainKeys("proposer", "nodeId");
    assertThat(CommandProposals.NONE.find(id)).isEmpty();
  }
}
