// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.filter.AgentCredentialFilter;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineConfig;
import com.infenia.yukta.plugin.control.engine.EngineContext;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.supervisor.agent.AgentHub;
import com.infenia.yukta.service.supervisor.agent.HttpEngineFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The agent API turns what an application sends into directives of its supervisor. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.CloseResource",
  "PMD.CouplingBetweenObjects",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.UseConcurrentHashMap"
})
class AgentApiControllerTest {

  private final AgentHub hub = new AgentHub();
  private final com.infenia.yukta.service.supervisor.SupervisorHost host =
      org.mockito.Mockito.mock(com.infenia.yukta.service.supervisor.SupervisorHost.class);
  private final AgentApiController controller = new AgentApiController(hub, host);
  private final List<Directive> received = new ArrayList<>();

  private static Signal review(final String id) {
    return new Signal.Review(
        id,
        new com.infenia.yukta.plugin.control.ControlRequest(
            new PauseWorkflowCommand("run1"), null, "s", "w"),
        Duration.ofMinutes(1));
  }

  private static Signal delegated(final String id) {
    return new Signal.Delegated(
        id, com.infenia.yukta.plugin.control.DelegationKind.APPROVAL, "c", Map.of());
  }

  /** Signals the agent was sent, so directives may name them as their cause. */
  private static final Flux<Signal> SENT =
      Flux.<Signal>just(
              new Signal.Event(
                  "e5",
                  "e5",
                  new com.infenia.yukta.plugin.control.ExecutionEvent.WorkflowStatusChanged(
                      "run1", "s", "w", "RUNNING", Instant.EPOCH)),
              delegated("q1"),
              review("r1"),
              review("r2"),
              delegated("d1"),
              delegated("d2"))
          .concatWith(Flux.never());

  private static MockServerWebExchange as(final String supervisor) {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/agent/v1/self"));
    exchange.getAttributes().put(AgentCredentialFilter.SUPERVISOR_ATTRIBUTE, supervisor);
    return exchange;
  }

  private SupervisorEngine running(final EngineReads reads) {
    return running(reads, SENT);
  }

  private SupervisorEngine running(final EngineReads reads, final Flux<Signal> signals) {
    final SupervisorEngine engine =
        new HttpEngineFactory(hub)
            .create(
                new EngineConfig(Map.of()),
                new EngineContext() {
                  @Override
                  public String self() {
                    return "a1";
                  }

                  @Override
                  public Flux<Instant> ticks(final Duration period) {
                    return Flux.never();
                  }

                  @Override
                  public Map<String, Object> state() {
                    return Map.of();
                  }

                  @Override
                  public EngineReads reads() {
                    return reads;
                  }
                });
    engine.run(signals).subscribe(received::add);
    return engine;
  }

  @Test
  void selfSaysWhoThisIsAndWhetherTheEngineRuns() {
    StepVerifier.create(controller.self(as("a1")))
        .assertNext(r -> assertThat(r.data().connected()).isFalse())
        .verifyComplete();
    running(EngineReads.NONE);
    StepVerifier.create(controller.self(as("a1")))
        .assertNext(
            r -> {
              assertThat(r.data().supervisor()).isEqualTo("a1");
              assertThat(r.data().contract()).isEqualTo(1);
              assertThat(r.data().connected()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void directiveBecomesCommandOfTheSupervisor() {
    running(EngineReads.NONE);
    StepVerifier.create(
            controller.directive(
                as("a1"),
                new AgentApiController.DirectiveBody(
                    "k1",
                    "event:e5",
                    "why",
                    "RUNNING",
                    Map.of("type", "PauseWorkflow", "executionId", "run1"))))
        .assertNext(r -> assertThat(r.status()).isEqualTo(202))
        .verifyComplete();

    assertThat(received)
        .containsExactly(
            new Directive.Command(
                "k1", new PauseWorkflowCommand("run1"), Cause.event("e5"), "why", "RUNNING"));
  }

  @Test
  void badDirectiveIsRefusedBeforeItReachesTheSupervisor() {
    running(EngineReads.NONE);
    final Map<String, Object> pause = Map.of("type", "PauseWorkflow", "executionId", "r");
    assertThatThrownBy(
            () ->
                controller.directive(
                    as("a1"), new AgentApiController.DirectiveBody("", null, null, null, pause)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                controller.directive(
                    as("a1"),
                    new AgentApiController.DirectiveBody(
                        "k", null, null, null, Map.of("type", "PauseWorkflow"))))
        .hasMessageContaining("executionId");
    assertThatThrownBy(
            () ->
                controller.directive(
                    as("a1"),
                    new AgentApiController.DirectiveBody(
                        "k", null, null, null, Map.of("type", "Nope", "executionId", "r"))))
        .hasMessageContaining("Not a command");
    assertThatThrownBy(
            () ->
                controller.directive(
                    as("a1"),
                    new AgentApiController.DirectiveBody("k", "whenever", null, null, pause)))
        .hasMessageContaining("cause must be");
    assertThatThrownBy(
            () ->
                controller.directive(
                    as("a1"), new AgentApiController.DirectiveBody("k", null, null, null, null)))
        .hasMessageContaining("needs a key and a command");
    assertThat(received).isEmpty();
  }

  @Test
  void everyCauseIsRead() {
    running(EngineReads.NONE);
    final Map<String, Object> pause = Map.of("type", "PauseWorkflow", "executionId", "r");
    for (final String cause : new String[] {null, "initiative", "tick", "answers:q1"}) {
      controller
          .directive(
              as("a1"), new AgentApiController.DirectiveBody("k" + cause, cause, "r", null, pause))
          .block();
    }
    assertThat(received)
        .extracting(d -> ((Directive.Command) d).cause().text())
        .containsExactly("initiative", "initiative", "tick", "answers:q1");
  }

  @Test
  void reviewsAndDelegationsAreAnswered() {
    running(EngineReads.NONE);
    controller.review(as("a1"), "r1", new AgentApiController.VerdictBody("deny", "no")).block();
    controller
        .review(as("a1"), "r2", new AgentApiController.VerdictBody("NO_OPINION", null))
        .block();
    controller
        .delegation(as("a1"), "d1", new AgentApiController.AnswerBody(Map.of("ok", true)))
        .block();
    controller.delegation(as("a1"), "d2", new AgentApiController.AnswerBody(null)).block();

    assertThat(received)
        .containsExactly(
            new Directive.ReviewVerdict("r1", Directive.Verdict.DENY, "no"),
            new Directive.NoOpinion("r2"),
            new Directive.DelegationAnswer("d1", Map.of("ok", true)),
            new Directive.DelegationAnswer("d2", Map.of()));
    assertThatThrownBy(
            () ->
                controller.review(
                    as("a1"), "r3", new AgentApiController.VerdictBody("maybe", null)))
        .hasMessageContaining("verdict must be");
    assertThatThrownBy(
            () -> controller.review(as("a1"), "r3", new AgentApiController.VerdictBody(null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsComeFromTheEnginesReads() {
    final EngineReads reads =
        new EngineReads() {
          @Override
          public Flux<RunView> executions(final @Nullable String status, final int limit) {
            return Flux.just(new RunView("s", "run1", "w", status == null ? "ANY" : status, null));
          }

          @Override
          public Flux<com.infenia.yukta.plugin.input.InputRequest> openInputs(final int limit) {
            return Flux.empty();
          }
        };
    running(reads);
    StepVerifier.create(controller.executions(as("a1"), "RUNNING", 5))
        .assertNext(
            r ->
                assertThat(r.data())
                    .extracting(EngineReads.RunView::status)
                    .containsExactly("RUNNING"))
        .verifyComplete();
    StepVerifier.create(controller.inputs(as("a1"), 5))
        .assertNext(r -> assertThat(r.data()).isEmpty())
        .verifyComplete();
  }

  @Test
  void supervisorWithoutAnEngineIsNotFoundAndTheStreamNeedsOne() {
    assertThatThrownBy(() -> controller.inputs(as("ghost"), 5))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> controller.events(as("ghost"), -1))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                controller.self(
                    MockServerWebExchange.from(MockServerHttpRequest.get("/api/agent/v1/self"))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theStreamCarriesTheSignalsWithTheirKind() {
    final reactor.core.publisher.Sinks.Many<Signal> in =
        reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer();
    final SupervisorEngine engine = running(EngineReads.NONE, in.asFlux());
    StepVerifier.create(
            controller
                .events(as("a1"), -1)
                .map(e -> java.util.Objects.requireNonNull(e.data()).kind())
                .take(1))
        .then(() -> in.tryEmitNext(new Signal.Tick(Instant.EPOCH)))
        .expectNext("TICK")
        .verifyComplete();
    engine.close();
  }

  @Test
  void snapshotGivesTheCursorToResumeFrom() {
    final reactor.core.publisher.Sinks.Many<Signal> in =
        reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer();
    final SupervisorEngine engine = running(EngineReads.NONE, in.asFlux());
    in.tryEmitNext(new Signal.Tick(Instant.EPOCH));
    in.tryEmitNext(new Signal.Tick(Instant.EPOCH.plusSeconds(1)));
    org.mockito.Mockito.when(host.held("a1")).thenReturn(List.of("run9"));

    StepVerifier.create(controller.snapshot(as("a1")))
        .assertNext(
            r -> {
              assertThat(r.data().cursor()).isEqualTo(2);
              assertThat(r.data().executions()).isEmpty();
              assertThat(r.data().inputs()).isEmpty();
              assertThat(r.data().held()).containsExactly("run9");
            })
        .verifyComplete();
    // after cursor 1 only the second signal is replayed, then the live ones
    StepVerifier.create(
            controller
                .events(as("a1"), 1)
                .map(e -> java.util.Objects.requireNonNull(e.data()).cursor())
                .take(2))
        .expectNext(2L)
        .then(() -> in.tryEmitNext(new Signal.Tick(Instant.EPOCH.plusSeconds(2))))
        .expectNext(3L)
        .verifyComplete();
    engine.close();
  }

  private EngineReads three() {
    return new EngineReads() {
      @Override
      public Flux<RunView> executions(final @Nullable String status, final int limit) {
        return Flux.just(
                new RunView("s", "r1", "w", "RUNNING", null),
                new RunView("s", "r2", "x", "RUNNING", null),
                new RunView("s", "r3", "w", "SUCCESS", null))
            .filter(run -> status == null || status.equals(run.status()));
      }

      @Override
      public Flux<com.infenia.yukta.plugin.input.InputRequest> openInputs(final int limit) {
        return Flux.empty();
      }
    };
  }

  private Directive.Command sent(final int index) {
    return (Directive.Command) received.get(index);
  }

  @Test
  void selectorIsExpandedToTheExecutionsItNamesEachSentAlone() {
    running(three());
    final Map<String, Object> pause = Map.of("type", "PauseWorkflow");
    final java.util.function.Function<String, Map<String, Object>> aim =
        selector -> {
          final Map<String, Object> command = new java.util.HashMap<>(pause);
          command.put("target", selector);
          return command;
        };

    controller
        .directive(
            as("a1"),
            new AgentApiController.DirectiveBody(
                "k", "tick", "busy", "RUNNING", aim.apply("owned.running")))
        .block();
    controller
        .directive(
            as("a1"),
            new AgentApiController.DirectiveBody("w", "tick", "r", null, aim.apply("workflow:w")))
        .block();
    controller
        .directive(
            as("a1"),
            new AgentApiController.DirectiveBody("e", "tick", "r", null, aim.apply("execution:r9")))
        .block();

    assertThat(received)
        .extracting(d -> ((Directive.Command) d).key())
        .containsExactly("k#r1", "k#r2", "w#r1", "w#r3", "e#r9");
    assertThat(sent(0).command()).isEqualTo(new PauseWorkflowCommand("r1"));
    assertThat(sent(0).ifStatus()).isEqualTo("RUNNING");
  }

  @Test
  void heldMeansWhatThisSupervisorPausedAndBadSelectorIsRefused() {
    running(three());
    org.mockito.Mockito.when(host.held("a1")).thenReturn(List.of("r7", "r8"));

    final var response =
        controller
            .directive(
                as("a1"),
                new AgentApiController.DirectiveBody(
                    "h", "tick", "r", null, Map.of("type", "ResumeWorkflow", "target", "held")))
            .block();

    assertThat(response.message()).contains("2 executions");
    assertThat(received).hasSize(2);
    assertThatThrownBy(
            () ->
                controller.directive(
                    as("a1"),
                    new AgentApiController.DirectiveBody(
                        "x",
                        "tick",
                        "r",
                        null,
                        Map.of("type", "PauseWorkflow", "target", "everything"))))
        .hasMessageContaining("target must be");
  }

  @Test
  void selectorIsCappedAndBarePrefixIsRefused() {
    running(three());
    final List<String> many =
        java.util.stream.IntStream.range(0, 250).mapToObj(i -> "r" + i).toList();
    org.mockito.Mockito.when(host.held("a1")).thenReturn(many);

    final var response =
        controller
            .directive(
                as("a1"),
                new AgentApiController.DirectiveBody(
                    "h", "tick", "r", null, Map.of("type", "ResumeWorkflow", "target", "held")))
            .block();

    assertThat(response.message()).contains("200 executions (the most allowed)");
    for (final String bare : new String[] {"execution:", "workflow:"}) {
      assertThatThrownBy(
              () ->
                  controller.directive(
                      as("a1"),
                      new AgentApiController.DirectiveBody(
                          "x", "tick", "r", null, Map.of("type", "PauseWorkflow", "target", bare))))
          .hasMessageContaining("target must be");
    }
  }

  @Test
  void directiveToStoppedSupervisorIsConflict() {
    final SupervisorEngine engine = running(three());
    final var pending =
        controller.directive(
            as("a1"),
            new AgentApiController.DirectiveBody(
                "w", "tick", "r", null, Map.of("type", "PauseWorkflow", "target", "workflow:w")));
    engine.close();
    assertThatThrownBy(pending::block)
        .isInstanceOf(com.infenia.yukta.service.ConflictException.class);
  }

  /** Whatever an agent sends, the server answers with a refusal it understands, never a crash. */
  @Test
  void garbageInDirectiveIsOnlyEverAnIllegalArgument() {
    running(EngineReads.NONE);
    final java.util.Random random = new java.util.Random(42);
    final Object[] junk = {
      null,
      "",
      " ",
      "x",
      7,
      -1L,
      3.5,
      true,
      List.of(),
      List.of(1, 2),
      Map.of(),
      Map.of("a", Map.of("b", List.of())),
      "x".repeat(5000),
      new int[0]
    };
    final String[] types = {
      "PauseWorkflow", "StopWorkflow", "AnswerInput", "SkipNode", "SetPriority", "Nope", "", null
    };
    for (int i = 0; i < 300; i++) {
      final Map<String, Object> command = new java.util.HashMap<>();
      command.put("type", types[random.nextInt(types.length)]);
      if (random.nextBoolean()) {
        command.put("executionId", junk[random.nextInt(junk.length)]);
      }
      for (final String key :
          new String[] {"nodeId", "priority", "mode", "requestId", "target", "decision"}) {
        if (random.nextInt(3) == 0) {
          command.put(key, junk[random.nextInt(junk.length)]);
        }
      }
      final AgentApiController.DirectiveBody body =
          new AgentApiController.DirectiveBody(
              (String) (random.nextBoolean() ? "k" + i : junk[random.nextInt(2)]),
              random.nextBoolean() ? null : "event:" + junk[random.nextInt(6)],
              null,
              null,
              command);
      try {
        controller.directive(as("a1"), body).block(Duration.ofSeconds(5));
      } catch (final IllegalArgumentException | ClassCastException expected) {
        // a refusal; ClassCastException only from the test's own cast above
      }
    }
  }

  @Test
  void pollReturnsWhatIsThereAtOnceAndAnEmptyBatchWhenNothingComes() {
    final reactor.core.publisher.Sinks.Many<Signal> in =
        reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer();
    final SupervisorEngine engine = running(EngineReads.NONE, in.asFlux());
    in.tryEmitNext(new Signal.Tick(Instant.EPOCH));
    in.tryEmitNext(new Signal.Tick(Instant.EPOCH.plusSeconds(1)));

    StepVerifier.create(controller.next(as("a1"), 0, 5))
        .assertNext(
            r -> {
              assertThat(r.data().signals()).hasSize(2);
              assertThat(r.data().cursor()).isEqualTo(2);
            })
        .verifyComplete();
    // nothing after cursor 2: it gives up after the wait and keeps the cursor
    StepVerifier.create(controller.next(as("a1"), 2, 1))
        .assertNext(
            r -> {
              assertThat(r.data().signals()).isEmpty();
              assertThat(r.data().cursor()).isEqualTo(2);
            })
        .verifyComplete();
    engine.close();
  }

  @Test
  void pollWithNoWaitStillReturnsTheStoredSignals() {
    final reactor.core.publisher.Sinks.Many<Signal> in =
        reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer();
    final SupervisorEngine engine = running(EngineReads.NONE, in.asFlux());
    in.tryEmitNext(new Signal.Tick(Instant.EPOCH));

    // wait=0 is the default of the MCP next_signals tool: what is stored must come back
    StepVerifier.create(controller.next(as("a1"), 0, 0))
        .assertNext(r -> assertThat(r.data().signals()).hasSize(1))
        .verifyComplete();
    engine.close();
  }

  @Test
  void causeNamingNoSignalSentIsRefusedSoItCannotSkipTheInitiativeLimits() {
    running(EngineReads.NONE);
    final Map<String, Object> pause = Map.of("type", "PauseWorkflow", "executionId", "r");
    for (final String forged : new String[] {"event:e999"}) {
      assertThatThrownBy(
              () ->
                  controller.directive(
                      as("a1"),
                      new AgentApiController.DirectiveBody("k", forged, null, null, pause)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("names no signal sent");
    }
    assertThatThrownBy(
            () ->
                controller.directive(
                    as("a1"),
                    new AgentApiController.DirectiveBody("k", "answers:nobody", null, null, pause)))
        .isInstanceOf(com.infenia.yukta.service.ConflictException.class);
    assertThat(received).isEmpty();
  }

  @Test
  void answerIsTakenOnceAndOnlyForRequestThatWasSent() {
    running(EngineReads.NONE);
    final var allow = new AgentApiController.VerdictBody("ALLOW", null);
    controller.review(as("a1"), "r1", allow).block();
    assertThatThrownBy(() -> controller.review(as("a1"), "r1", allow))
        .isInstanceOf(com.infenia.yukta.service.ConflictException.class);
    assertThatThrownBy(() -> controller.review(as("a1"), "unknown", allow))
        .isInstanceOf(com.infenia.yukta.service.ConflictException.class);
    final Map<String, Object> pause = Map.of("type", "PauseWorkflow", "executionId", "r");
    controller
        .directive(
            as("a1"), new AgentApiController.DirectiveBody("k1", "answers:d1", null, null, pause))
        .block();
    assertThatThrownBy(
            () ->
                controller.directive(
                    as("a1"),
                    new AgentApiController.DirectiveBody("k2", "answers:d1", null, null, pause)))
        .hasMessageContaining("answered before");
  }

  @Test
  void lateVerdictIsRefused() {
    running(EngineReads.NONE);
    final AgentHub.Channel open = hub.channel("a1").orElseThrow();
    assertThat(open.claimAnswer("verdict", "r2", Instant.now().plus(Duration.ofMinutes(2))))
        .isFalse();
    assertThat(open.claimAnswer("verdict", "r2", Instant.now())).isTrue();
  }

  @Test
  void revokedCredentialEndsTheOpenStream() {
    running(EngineReads.NONE);
    StepVerifier.create(controller.events(as("a1"), 0))
        .expectNextCount(6)
        .then(() -> hub.credentialChanged("a1"))
        .thenConsumeWhile(event -> true)
        .verifyComplete();
  }

  @Test
  void disablingTheSupervisorEndsTheStreamAndRefusesTheNextCall() {
    final SupervisorEngine engine = running(EngineReads.NONE);
    StepVerifier.create(controller.events(as("a1"), 0))
        .expectNextCount(6)
        // detaching or disabling the supervisor closes its engine, which closes the channel
        .then(engine::close)
        .thenConsumeWhile(event -> true)
        .verifyComplete();
    assertThatThrownBy(() -> controller.snapshot(as("a1"))).isInstanceOf(NotFoundException.class);
  }

  @Test
  void anExecutionItsLogsItsWorkflowAndTheSchedulerComeFromTheEnginesReads() {
    final EngineReads.RunView view = new EngineReads.RunView("s", "run1", "w", "RUNNING", null);
    final EngineReads reads =
        new EngineReads() {
          @Override
          public Flux<RunView> executions(final @Nullable String status, final int limit) {
            return Flux.empty();
          }

          @Override
          public Flux<com.infenia.yukta.plugin.input.InputRequest> openInputs(final int limit) {
            return Flux.empty();
          }

          @Override
          public Mono<RunDetail> execution(final String executionId) {
            return "run1".equals(executionId)
                ? Mono.just(new RunDetail(view, Map.of("build", "RUNNING")))
                : Mono.empty();
          }

          @Override
          public Flux<String> logs(final String executionId, final int tail) {
            return Flux.just("INFO build: hello");
          }

          @Override
          public Mono<WorkflowView> workflow(final String sessionId, final String workflowId) {
            return Mono.just(new WorkflowView(workflowId, List.of(), List.of()));
          }

          @Override
          public Mono<SchedulerView> scheduler() {
            return Mono.just(new SchedulerView(4, 2, 1, 0));
          }
        };
    running(reads);

    StepVerifier.create(controller.execution(as("a1"), "run1"))
        .assertNext(r -> assertThat(r.data().nodes()).containsEntry("build", "RUNNING"))
        .verifyComplete();
    StepVerifier.create(controller.logs(as("a1"), "run1", 10))
        .assertNext(r -> assertThat(r.data()).containsExactly("INFO build: hello"))
        .verifyComplete();
    StepVerifier.create(controller.workflow(as("a1"), "s", "w"))
        .assertNext(r -> assertThat(r.data().workflowId()).isEqualTo("w"))
        .verifyComplete();
    StepVerifier.create(controller.scheduler(as("a1")))
        .assertNext(r -> assertThat(r.data().maxConcurrent()).isEqualTo(4))
        .verifyComplete();
  }

  @Test
  void whatIsNotOwnedOrNotReadableIsNotFound() {
    running(EngineReads.NONE);

    StepVerifier.create(controller.execution(as("a1"), "run1"))
        .expectError(NotFoundException.class)
        .verify();
    StepVerifier.create(controller.logs(as("a1"), "run1", 10))
        .expectError(NotFoundException.class)
        .verify();
    StepVerifier.create(controller.workflow(as("a1"), "s", "w"))
        .expectError(NotFoundException.class)
        .verify();
    StepVerifier.create(controller.scheduler(as("a1")))
        .expectError(NotFoundException.class)
        .verify();
  }
}
