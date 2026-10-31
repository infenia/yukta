// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.ShortMethodName",
  "PMD.UnitTestShouldIncludeAssert"
})
@NoArgsConstructor
class SupervisorHostTest {

  private final ExecutionEventFeed feed = new ExecutionEventFeed();
  private final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);
  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private SupervisorHost host;

  @AfterEach
  void tearDown() {
    if (host != null) {
      host.stop();
    }
  }

  private static ExecutionSupervisor supervisor(
      final String id, final Function<Flux<ExecutionEvent>, Flux<ExecutionControlCommand>> logic) {
    return new ExecutionSupervisor() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
        return logic.apply(events);
      }
    };
  }

  private void start(
      final ExecutionSupervisor supervisor, final boolean dryRun, final int perSecond) {
    start(supervisor, dryRun, perSecond, 10_000);
  }

  private void start(
      final ExecutionSupervisor supervisor,
      final boolean dryRun,
      final int perSecond,
      final int bufferSize) {
    host =
        new SupervisorHost(
            providerOf(supervisor), feed, dispatcher, log, meters, dryRun, perSecond, bufferSize);
    host.start();
  }

  private static ExecutionEvent failed(final String execution) {
    return new ExecutionEvent.NodeStatusChanged(
        execution, "s", "wf", "n", "echo", "FAILURE", null, Instant.now());
  }

  private static Flux<ExecutionControlCommand> pauseOnFailure(final Flux<ExecutionEvent> events) {
    return events
        .ofType(ExecutionEvent.NodeStatusChanged.class)
        .filter(e -> "FAILURE".equals(e.status()))
        .map(e -> new ExecutionControlCommand.PauseWorkflowCommand(e.executionId()));
  }

  @Test
  void start_scopedSupervisor_seesOnlyEventsOfItsScope() {
    final List<String> seen = new CopyOnWriteArrayList<>();
    final ExecutionSupervisor scoped =
        new ExecutionSupervisor() {
          @Override
          public String id() {
            return "scoped";
          }

          @Override
          public SupervisorScope scope() {
            return new SupervisorScope.Workflow("wf", "s");
          }

          @Override
          public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
            return events.doOnNext(e -> seen.add(e.executionId())).thenMany(Flux.empty());
          }
        };
    start(scoped, false, 10);

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(
                  new ExecutionEvent.WorkflowStatusChanged(
                      "other-session", "x", "wf", "RUNNING", Instant.now()));
              feed.publish(
                  new ExecutionEvent.WorkflowStatusChanged(
                      "other-wf", "s", "y", "RUNNING", Instant.now()));
              feed.publish(
                  new ExecutionEvent.WorkflowStatusChanged(
                      "mine", "s", "wf", "RUNNING", Instant.now()));
              assertThat(seen).contains("mine");
            });
    assertThat(seen).doesNotContain("other-session", "other-wf");
    assertThat(host.status().getFirst().scope()).isEqualTo("WORKFLOW");
    assertThat(host.status().getFirst().scopeSessionId()).isEqualTo("s");
  }

  @Test
  void start_emittedCommand_appliedWithSupervisorIdAsActor() {
    final AtomicReference<String> actor = new AtomicReference<>();
    when(dispatcher.dispatchOrdered(any()))
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  actor.set(context.get(ControlActor.CONTEXT_KEY));
                  return Mono.empty();
                }));
    start(supervisor("rules", SupervisorHostTest::pauseOnFailure), false, 10);

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(failed("e1"));
              verify(dispatcher, org.mockito.Mockito.atLeastOnce())
                  .dispatchOrdered(new ExecutionControlCommand.PauseWorkflowCommand("e1"));
            });
    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(actor.get()).isEqualTo("rules"));
    assertThat(
            meters
                .counter("yukta.supervisor.commands", "id", "rules", "outcome", "applied")
                .count())
        .isPositive();
  }

  @Test
  void start_dryRun_nothingAppliedButRecorded() {
    final List<com.infenia.yukta.security.AuditRecord> sunk = new CopyOnWriteArrayList<>();
    start(supervisor("rules", SupervisorHostTest::pauseOnFailure), true, 10);
    host.setAuditSink(sunk::add);

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(failed("e1"));
              assertThat(log.readAll())
                  .anySatisfy(
                      entry -> {
                        assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
                        assertThat(entry.status()).contains("[by rules]").contains("dry-run");
                      });
            });
    verify(dispatcher, never()).dispatchOrdered(any());
    assertThat(sunk)
        .anySatisfy(
            r -> {
              assertThat(r.actor()).isEqualTo("rules");
              assertThat(r.applied()).isFalse();
            });
  }

  @Test
  void start_rejectedCommand_isCountedAndSupervisorKeepsRunning() {
    when(dispatcher.dispatchOrdered(any()))
        .thenReturn(Mono.error(new ControlRejectedException("no")))
        .thenReturn(Mono.empty());
    start(supervisor("rules", SupervisorHostTest::pauseOnFailure), false, 10);

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(failed("e1"));
              verify(dispatcher, org.mockito.Mockito.atLeast(2)).dispatchOrdered(any());
            });
    assertThat(
            meters
                .counter("yukta.supervisor.commands", "id", "rules", "outcome", "rejected")
                .count())
        .isPositive();
  }

  @Test
  void start_overRateLimit_extraCommandsDroppedAndRecorded() {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    final Flux<ExecutionControlCommand> burst =
        Flux.range(0, 5).map(i -> new ExecutionControlCommand.PauseWorkflowCommand("e" + i));
    start(supervisor("noisy", events -> burst.concatWith(events.then(Mono.empty()))), false, 2);

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        meters
                            .counter(
                                "yukta.supervisor.commands",
                                "id",
                                "noisy",
                                "outcome",
                                "rate-limited")
                            .count())
                    .isPositive());
    verify(dispatcher, org.mockito.Mockito.atMost(5)).dispatchOrdered(any());
    assertThat(log.readAll())
        .anySatisfy(e -> assertThat(e.status()).contains("rate limit exceeded"));
  }

  @Test
  void start_supervisorDoesNotSeeItsOwnControlOutcomes() {
    final List<ExecutionEvent> seen = new CopyOnWriteArrayList<>();
    start(
        supervisor("rules", events -> events.doOnNext(seen::add).thenMany(Flux.empty())),
        false,
        10);
    final var own =
        new ExecutionEvent.ControlOutcome(
            "e1", null, null, "PauseWorkflowCommand", "rules", true, null, Instant.now());
    final var other =
        new ExecutionEvent.ControlOutcome(
            "e1", null, null, "PauseWorkflowCommand", "human", true, null, Instant.now());

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(own);
              feed.publish(other);
              assertThat(seen).contains(other).doesNotContain(own);
            });
  }

  @Test
  void start_supervisorThatFails_isResubscribed() {
    final AtomicInteger subscriptions = new AtomicInteger();
    start(
        supervisor(
            "flaky",
            _ ->
                Flux.defer(
                    () -> {
                      subscriptions.incrementAndGet();
                      return Flux.error(new IllegalStateException("boom"));
                    })),
        false,
        10);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(subscriptions.get()).isGreaterThanOrEqualTo(2));
  }

  @Test
  void stop_disposesSubscriptions() {
    final AtomicInteger subscribed = new AtomicInteger();
    final AtomicInteger cancelled = new AtomicInteger();
    start(
        supervisor(
            "rules",
            events ->
                events
                    .doOnSubscribe(_ -> subscribed.incrementAndGet())
                    .doOnCancel(cancelled::incrementAndGet)
                    .thenMany(Flux.empty())),
        false,
        10);
    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(subscribed.get()).isEqualTo(1));

    host.stop();

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(cancelled.get()).isEqualTo(1));
  }

  @Test
  void start_supervisorRunsOffThePublishingThread() throws InterruptedException {
    final AtomicReference<String> seenOn = new AtomicReference<>();
    start(
        supervisor(
            "off-thread",
            events ->
                events
                    .doOnNext(_ -> seenOn.set(Thread.currentThread().getName()))
                    .thenMany(Flux.empty())),
        false,
        10);

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final Thread publisher =
                  new Thread(() -> feed.publish(failed("e1")), "tracker-like-thread");
              publisher.start();
              publisher.join();
              assertThat(seenOn.get()).isNotNull().isNotEqualTo("tracker-like-thread");
            });
  }

  @Test
  void start_slowSupervisor_dropsEventsAndCountsThem() {
    start(
        supervisor(
            "slow",
            events ->
                events
                    .limitRate(1)
                    .concatMap(_ -> Mono.delay(Duration.ofSeconds(30)))
                    .thenMany(Flux.empty())),
        false,
        10,
        4);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              for (int i = 0; i < 2000; i++) {
                feed.publish(failed("e" + i));
              }
              assertThat(meters.counter("yukta.tracker.dropped", "kind", "supervisor").count())
                  .isPositive();
            });
  }

  @Test
  void start_slowSupervisor_isToldHowManyEventsItMissed() {
    final List<ExecutionEvent.EventsMissed> gaps = new CopyOnWriteArrayList<>();
    start(
        supervisor(
            "slow",
            events ->
                events
                    .limitRate(1)
                    .concatMap(event -> Mono.delay(Duration.ofMillis(5)).thenReturn(event))
                    .ofType(ExecutionEvent.EventsMissed.class)
                    .doOnNext(gaps::add)
                    .then(Mono.<ExecutionControlCommand>empty())
                    .flux()),
        false,
        10,
        4);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              for (int i = 0; i < 2000; i++) {
                feed.publish(failed("e" + i));
              }
              assertThat(gaps).isNotEmpty();
              assertThat(gaps.getFirst().count()).isPositive();
            });
  }

  /** A control plane with a class name, as real ones have. */
  private static final class NamedPlane implements ControlPlanePlugin {
    @Override
    public String id() {
      return "plane";
    }

    @Override
    public int order() {
      return 5;
    }

    @Override
    public Mono<ControlDecision> decide(final ExecutionEvent event) {
      return Mono.just(ControlDecision.none(ControlDecision.Source.MODEL));
    }
  }

  @Test
  void status_describesEachSupervisorWithItsCommandCounts() {
    final ControlPlanePlugin plane = new NamedPlane();
    final ExecutionSupervisor plain = supervisor("rules", SupervisorHostTest::pauseOnFailure);
    host =
        new SupervisorHost(
            providerOf(plane, plain), feed, dispatcher, log, meters, true, 7, 10_000);
    meters.counter("yukta.supervisor.commands", "id", "rules", "outcome", "applied").increment(3);
    meters.counter("yukta.supervisor.commands", "id", "rules", "outcome", "rejected").increment();
    meters.counter("yukta.supervisor.commands", "id", "rules", "outcome", "dry-run").increment(2);
    meters
        .counter("yukta.supervisor.commands", "id", "rules", "outcome", "rate-limited")
        .increment(4);

    final List<SupervisorInfo> status = host.status();

    assertThat(status)
        .extracting(SupervisorInfo::id, SupervisorInfo::kind, SupervisorInfo::order)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("rules", "SUPERVISOR", 0),
            org.assertj.core.groups.Tuple.tuple("plane", "CONTROL_PLANE", 5));
    assertThat(status.getFirst())
        .isEqualTo(
            new SupervisorInfo(
                "rules",
                "SUPERVISOR",
                status.getFirst().type(),
                0,
                true,
                7,
                3,
                1,
                2,
                4,
                "GLOBAL",
                null,
                null));
    assertThat(status.get(1).applied()).isZero();
    assertThat(status.getFirst().type()).contains("SupervisorHostTest");
    assertThat(status.get(1).type()).isEqualTo("NamedPlane");
  }
}
