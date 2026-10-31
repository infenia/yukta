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

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Attaching and detaching supervisors at run time. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestShouldIncludeAssert"
})
class AttachedSupervisorTest {

  private final ExecutionEventFeed feed = new ExecutionEventFeed();

  private final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private final AttachedSupervisors attached = new AttachedSupervisors();

  private final SupervisorHost host =
      new SupervisorHost(
          providerOf(), feed, dispatcher, new InMemoryExecutionLogStore(), meters, false, 10, 1000);

  private final List<String> seen = new CopyOnWriteArrayList<>();

  /** Records the runs it sees and pauses every run it sees; denies every review. */
  private final ExecutionSupervisor observer =
      new ExecutionSupervisor() {
        @Override
        public String id() {
          return "made";
        }

        @Override
        public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
          return events
              .doOnNext(e -> seen.add(e.executionId()))
              .map(e -> new ExecutionControlCommand.PauseWorkflowCommand(e.executionId()));
        }

        @Override
        public Mono<Decision> review(final ControlRequest request) {
          return Mono.just(Decision.deny("no"));
        }
      };

  private BoundSupervisor bound(final String id, final SupervisorScope scope, final boolean dry) {
    return new BoundSupervisor(new SupervisorBinding(id, "p", scope, true), observer, dry);
  }

  @AfterEach
  void tearDown() {
    host.stop();
  }

  private void publishUntilSeen(final String execution, final String session) {
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(
                  new ExecutionEvent.WorkflowStatusChanged(
                      execution, session, "w", "RUNNING", Instant.now()));
              assertThat(seen).contains(execution);
            });
  }

  @Test
  void attachedSupervisorSeesOnlyItsScopeUntilDetached() {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    host.setAttachedSupervisors(attached);
    host.attach(bound("b1", new SupervisorScope.Session("A"), false));

    publishUntilSeen("in-A", "A");
    assertThat(host.status().getFirst().dryRun()).isFalse();
    feed.publish(
        new ExecutionEvent.WorkflowStatusChanged("in-B", "B", "w", "RUNNING", Instant.now()));
    assertThat(host.detach("b1")).isTrue();
    feed.publish(
        new ExecutionEvent.WorkflowStatusChanged("after", "A", "w", "RUNNING", Instant.now()));

    assertThat(seen).doesNotContain("in-B", "after");
    assertThat(host.detach("b1")).isFalse();
    verify(dispatcher, never())
        .dispatchOrdered(new ExecutionControlCommand.PauseWorkflowCommand("after"));
  }

  @Test
  void reattachingReplacesTheRunningSupervisorAndStatusShowsIt() {
    host.setAttachedSupervisors(attached);
    host.attach(bound("b1", new SupervisorScope.Session("A"), false));
    host.attach(bound("b1", new SupervisorScope.Workflow("w", "A"), true));

    assertThat(host.status())
        .singleElement()
        .satisfies(
            info -> {
              assertThat(info.id()).isEqualTo("b1");
              assertThat(info.kind()).isEqualTo("SUPERVISOR");
              assertThat(info.scope()).isEqualTo("WORKFLOW");
              assertThat(info.scopeSessionId()).isEqualTo("A");
              assertThat(info.dryRun()).isTrue();
            });
  }

  @Test
  void detachingRemovesTheCommandCountersOfTheBinding() {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    host.setAttachedSupervisors(attached);
    host.attach(bound("b1", new SupervisorScope.Global(), false));
    publishUntilSeen("r1", "A");
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(meters.find("yukta.supervisor.commands").tag("id", "b1").counters())
                    .isNotEmpty());

    host.detach("b1");

    assertThat(meters.find("yukta.supervisor.commands").tag("id", "b1").counters()).isEmpty();
  }

  @Test
  void attachedDryRunSupervisorOnlyRecords() {
    host.setAttachedSupervisors(attached);
    host.attach(bound("b1", new SupervisorScope.Global(), true));

    publishUntilSeen("e1", "A");

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(host.status().getFirst().recorded()).isPositive());
    verify(dispatcher, never()).dispatchOrdered(any());
  }

  @Test
  void theServerWideDryRunAppliesToAttachedSupervisorsToo() {
    final SupervisorHost recordingHost =
        new SupervisorHost(
            providerOf(),
            feed,
            dispatcher,
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            true,
            10,
            1000);
    try {
      recordingHost.attach(bound("b1", new SupervisorScope.Global(), false));

      assertThat(recordingHost.status().getFirst().dryRun()).isTrue();
    } finally {
      recordingHost.stop();
    }
  }

  @Test
  void stopEndsAttachedSupervisorsToo() {
    host.setAttachedSupervisors(attached);
    host.attach(bound("b1", new SupervisorScope.Global(), false));
    host.stop();

    assertThat(host.detach("b1")).isTrue();
  }

  @Test
  void theReviewerAsksAttachedSupervisorsWhoseScopeCoversTheRun() {
    final ControlReviewer reviewer = new ControlReviewer(providerOf());
    reviewer.setAttachedSupervisors(attached);
    attached.put(bound("b1", new SupervisorScope.Session("A"), false));
    attached.put(bound("b2", new SupervisorScope.Session("B"), false));

    final ControlRequest inA =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "A", "w");
    final ControlRequest inC =
        new ControlRequest(new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "C", "w");

    StepVerifier.create(reviewer.review(inA)).expectError(ControlRejectedException.class).verify();
    StepVerifier.create(reviewer.review(inC)).verifyComplete();
    assertThat(attached.remove("b1")).isNotNull();
    StepVerifier.create(reviewer.review(inA)).verifyComplete();
  }

  @Test
  void boundSupervisorAnswersToItsBindingAndDoesWhatTheDelegateDoes() {
    final BoundSupervisor supervisor = bound("b1", new SupervisorScope.Session("A"), false);

    assertThat(supervisor.id()).isEqualTo("b1");
    assertThat(supervisor.order()).isZero();
    assertThat(supervisor.scope()).isEqualTo(new SupervisorScope.Session("A"));
    StepVerifier.create(
            supervisor.review(
                new ControlRequest(
                    new ExecutionControlCommand.PauseWorkflowCommand("e"), null, "A", "w")))
        .expectNext(Decision.deny("no"))
        .verifyComplete();
  }

  @Test
  void decisionsRecordWhatBecameOfEachCommandNewestFirst() {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    host.setAttachedSupervisors(attached);
    host.attach(bound("live", new SupervisorScope.Global(), false));
    host.attach(bound("dry", new SupervisorScope.Global(), true));

    publishUntilSeen("e1", "A");

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(host.decisions("live", 10))
                  .extracting(SupervisorDecision::outcome)
                  .contains("applied");
              assertThat(host.decisions("dry", 10))
                  .extracting(SupervisorDecision::outcome)
                  .contains("dry-run");
            });
    assertThat(host.decisions(null, 1)).hasSize(1);
    assertThat(host.decisions("nobody", 10)).isEmpty();
    assertThat(host.decisions(null, 0)).isEmpty();
  }

  @Test
  void onlyTheNewestFiveHundredDecisionsAreKept() {
    final ExecutionSupervisor chatty =
        new ExecutionSupervisor() {
          @Override
          public String id() {
            return "chatty";
          }

          @Override
          public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
            return events
                .take(1)
                .flatMap(
                    e ->
                        Flux.range(0, 600)
                            .map(i -> new ExecutionControlCommand.PauseWorkflowCommand("e" + i)));
          }
        };
    host.setAttachedSupervisors(attached);
    host.attach(
        new BoundSupervisor(
            new SupervisorBinding("chatty", "p", new SupervisorScope.Global(), true),
            chatty,
            true));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(
                  new ExecutionEvent.WorkflowStatusChanged(
                      "x", "A", "w", "RUNNING", Instant.now()));
              assertThat(host.decisions("chatty", 1000)).hasSize(500);
            });
    assertThat(host.decisions("chatty", 1000).getFirst().executionId()).isEqualTo("e599");
  }

  @Test
  void rejectedCommandsAreRecordedWithTheirReason() {
    when(dispatcher.dispatchOrdered(any()))
        .thenReturn(Mono.error(new ControlRejectedException("no")));
    host.setAttachedSupervisors(attached);
    host.attach(bound("b1", new SupervisorScope.Global(), false));

    publishUntilSeen("e1", "A");

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(host.decisions("b1", 10))
                    .anySatisfy(
                        d -> {
                          assertThat(d.outcome()).isEqualTo("rejected");
                          assertThat(d.reason()).contains("no");
                          assertThat(d.command()).isEqualTo("PauseWorkflowCommand");
                        }));
  }
}
