// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputFlow;
import com.infenia.yukta.service.input.InputTestSupport;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.BoundSupervisor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * The threats of the input request design, one test each, named after the threat. Where an earlier
 * phase already tests a threat next to its code, the test there is the one: token replay and tamper
 * ({@code AnswerTokensTest}, {@code TokenAnswerServiceTest}, {@code TokenAnswerControllerTest}),
 * actor spoofing ({@code ActorResolverTest}), a supervisor answering HIGH risk or for its own run
 * ({@code AnswerInputCommandProcessorTest}, {@code DefaultAnswerAuthorizerTest}), the tier rule
 * ({@code InputBrokerRoutesTest}), schema checks ({@code SchemaSubsetTest}, {@code
 * AnswerShapeTest}), the caps ({@code InputBrokerTest}) and a workflow supervisor forging its depth
 * ({@code ExecutionLogEventInitiatorTest}).
 */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.ExcessiveImports"})
class InputRequestThreatsTest {

  private final InputFlow flow = new InputFlow();

  private final AnswerInputCommandProcessor processor =
      new AnswerInputCommandProcessor(flow.guard, flow.broker);

  private static AnswerInputCommand answer(final String requestId) {
    return new AnswerInputCommand(
        "exec", requestId, null, "approve", null, Map.of(), "because", 0.9);
  }

  private InputRequest ask(final InputRisk risk) {
    flow.ask(
        InputTestSupport.spec(
            "k", InputKind.APPROVAL, risk, Audience.CURRENT_TIER, List.of(), null, Map.of()));
    return flow.onlyOpen();
  }

  private void attach(
      final String id, final SupervisorScope scope, final boolean canAnswer, final InputRisk max) {
    when(flow.attached.find(id))
        .thenReturn(
            Optional.of(
                new BoundSupervisor(
                    new SupervisorBinding(id, "p", scope, true),
                    mock(ExecutionSupervisor.class),
                    false,
                    new SupervisorCapabilities(null, canAnswer, max))));
  }

  @Test
  void pluginForgesWhichRunOrNodeIsAsking() {
    // the spec is all a plugin passes; the engine adds the run and the node
    assertThat(
            Arrays.stream(InputRequestSpec.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT)))
        .noneMatch(name -> name.contains("execution") || name.contains("node"));
  }

  @Test
  void supervisorAnswersAboveItsRiskCeiling() {
    attach("guard", new SupervisorScope.Global(), true, InputRisk.LOW);
    final InputRequest request = ask(InputRisk.MEDIUM);

    StepVerifier.create(
            processor
                .process(answer(request.id()))
                .contextWrite(InputFlow.asSupervisor("guard"))
                .then())
        .expectError(InputForbiddenException.class)
        .verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void supervisorAnswersWithoutTheRightToAnswer() {
    attach("guard", new SupervisorScope.Global(), false, InputRisk.HIGH);
    final InputRequest request = ask(InputRisk.LOW);

    StepVerifier.create(
            processor
                .process(answer(request.id()))
                .contextWrite(InputFlow.asSupervisor("guard"))
                .then())
        .expectError(InputForbiddenException.class)
        .verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void supervisorAnswersRequestOfAnotherSession() {
    attach("guard", new SupervisorScope.Session("another-session"), true, InputRisk.HIGH);
    final InputRequest request = ask(InputRisk.LOW);

    StepVerifier.create(
            processor
                .process(answer(request.id()))
                .contextWrite(InputFlow.asSupervisor("guard"))
                .then())
        .expectError(InputForbiddenException.class)
        .verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
    assertThat(flow.meters.counter("yukta.input.denied", "rule", "scope").count()).isEqualTo(1.0);
  }

  @Test
  void supervisorOfTheSessionMayAnswerLowRequests() {
    attach("guard", new SupervisorScope.Session("session"), true, InputRisk.LOW);
    final InputRequest request = ask(InputRisk.LOW);

    StepVerifier.create(
            processor
                .process(answer(request.id()))
                .contextWrite(InputFlow.asSupervisor("guard"))
                .then())
        .verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ANSWERED);
  }

  @Test
  void secretsInTheContextAreNeverInTheStoreTheEventsOrTheLog() {
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final List<ExecutionEvent> events = new CopyOnWriteArrayList<>();
    feed.events().subscribe(events::add);
    final var store = new com.infenia.yukta.service.input.InMemoryInputRequestStore(10);
    final InputBroker broker =
        new InputBroker(
            store,
            feed,
            log,
            new SimpleMeterRegistry(),
            new InputRedactor(List.of("deployToken")),
            InputTestSupport.limits(),
            new MutableClock());
    final String secret = "s3cr3t-value-9f2c";

    broker
        .ask(
            InputTestSupport.who("exec", "node"),
            InputTestSupport.spec(
                "k",
                InputKind.APPROVAL,
                InputRisk.LOW,
                Audience.CURRENT_TIER,
                List.of(),
                null,
                Map.of("deployToken", secret, "env", "staging")))
        .subscribe();

    org.awaitility.Awaitility.await()
        .untilAsserted(
            () -> assertThat(events).anyMatch(ExecutionEvent.InputRequested.class::isInstance));
    assertThat(store.list(null, null).collectList().block().toString()).doesNotContain(secret);
    assertThat(events.toString()).doesNotContain(secret);
    assertThat(log.readAll().toString()).doesNotContain(secret);
    assertThat(store.list(null, null).collectList().block().toString()).contains("staging");
  }

  @Test
  void noAnswerRejectionEscalationExpiryOrCancellationGoesUnrecorded() {
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
    final MutableClock clock = new MutableClock();
    final InputBroker broker =
        new InputBroker(
            new com.infenia.yukta.service.input.InMemoryInputRequestStore(10),
            new ExecutionEventFeed(),
            log,
            new SimpleMeterRegistry(),
            new InputRedactor(List.of()),
            InputTestSupport.limits(),
            clock);
    final List<String> ids = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      final String run = "run" + i;
      broker
          .ask(
              InputTestSupport.who(run, "node"),
              InputTestSupport.spec(
                  "k" + i,
                  i == 2 ? Duration.ofMinutes(1) : Duration.ofHours(1),
                  TimeoutAction.FAIL,
                  null))
          .subscribe(response -> {}, error -> {});
    }
    broker.listOpen(null, null).subscribe(request -> ids.add(request.id()));

    final Responder ann = new Responder(Responder.Type.HUMAN, "ann");
    broker
        .answer(
            ids.get(0),
            null,
            new InputResponse(ids.get(0), "go", "ok", Map.of(), ann, "fine", null, Instant.EPOCH))
        .block();
    broker.escalate(ids.get(1), "unsure", "ask a person", ann).block();
    clock.advance(Duration.ofMinutes(2));
    broker.sweep().block();
    broker.cancel(ids.get(3), "node-cancelled").block();

    for (int i = 0; i < ids.size(); i++) {
      final String requestId = ids.get(i);
      final List<String> events =
          log.readAll().stream()
              .map(ExecutionLogEvent::payload)
              .filter(payload -> payload != null && requestId.equals(payload.get("requestId")))
              .map(payload -> String.valueOf(payload.get(ExecutionLogEvent.INPUT_EVENT_KEY)))
              .toList();
      assertThat(events).as("request %d", i).contains("INPUT_REQUESTED");
    }
    assertThat(logged(log, "INPUT_ANSWERED")).containsEntry("responderId", "ann");
    assertThat(logged(log, "INPUT_ESCALATED")).containsEntry("responderId", "ann");
    assertThat(logged(log, "INPUT_EXPIRED")).containsKey("requestId");
    assertThat(logged(log, "INPUT_CANCELLED")).containsEntry("reason", "node-cancelled");
  }

  private static Map<String, Object> logged(
      final InMemoryExecutionLogStore log, final String event) {
    return log.readAll().stream()
        .map(ExecutionLogEvent::payload)
        .filter(
            payload ->
                payload != null && event.equals(payload.get(ExecutionLogEvent.INPUT_EVENT_KEY)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no log entry " + event));
  }
}
