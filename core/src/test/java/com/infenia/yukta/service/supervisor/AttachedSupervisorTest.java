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
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.ShortMethodName"})
class AttachedSupervisorTest {

  private final ExecutionEventFeed feed = new ExecutionEventFeed();

  private final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);

  private final AttachedSupervisors attached = new AttachedSupervisors();

  private final SupervisorHost host =
      new SupervisorHost(
          providerOf(),
          feed,
          dispatcher,
          new InMemoryExecutionLogStore(),
          new SimpleMeterRegistry(),
          false,
          10,
          1000);

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
}
