// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.review.AttachedSupervisors;
import com.infenia.yukta.service.review.BoundSupervisor;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import com.infenia.yukta.testing.Fixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the attached-supervisor tests share: a host on a feed, the supervisors attached to it, and
 * an observer that records the runs it sees and pauses each; it denies every review.
 *
 * @param feed the event feed the host listens to
 * @param dispatcher the (mock) dispatcher the host sends commands to
 * @param meters the meter registry
 * @param attached the attached supervisors
 * @param host the host under test
 * @param seen the executions the observer saw
 * @param observer the supervisor attachments are bound to
 */
record AttachedHarness(
    ExecutionEventFeed feed,
    DirectiveDispatcher dispatcher,
    SimpleMeterRegistry meters,
    AttachedSupervisors attached,
    SupervisorHost host,
    List<String> seen,
    ExecutionSupervisor observer) {

  /**
   * Makes the harness.
   *
   * @return a new harness
   */
  /* default */ static AttachedHarness create() {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final List<String> seen = new CopyOnWriteArrayList<>();
    final ExecutionSupervisor observer = new Observer("made", seen::add);
    return new AttachedHarness(
        feed,
        dispatcher,
        meters,
        new AttachedSupervisors(),
        SupervisorHostFixture.create(
            providerOf(),
            feed,
            dispatcher,
            new InMemoryExecutionLogStore(),
            meters,
            false,
            10,
            1000),
        seen,
        observer);
  }

  /**
   * Binds the observer.
   *
   * @param id the attachment id
   * @param scope the scope
   * @param dry whether commands are only recorded
   * @return the bound supervisor
   */
  /* default */ BoundSupervisor bound(
      final String id, final SupervisorScope scope, final boolean dry) {
    return Fixtures.boundSupervisor(new SupervisorAttachment(id, "p", scope, true), observer, dry);
  }

  /**
   * Publishes an event until the observer has seen the run.
   *
   * @param execution the execution id
   * @param session the session id
   */
  /* default */ void publishUntilSeen(final String execution, final String session) {
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

  /**
   * The observer: it hands each run it sees to a sink, pauses it, and denies every review.
   *
   * @param id the supervisor id
   * @param sink receives the id of each execution the observer sees
   */
  private record Observer(String id, Consumer<String> sink) implements ExecutionSupervisor {
    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return events
          .doOnNext(e -> sink.accept(e.executionId()))
          .map(e -> new ExecutionControlCommand.PauseWorkflowCommand(e.executionId()));
    }

    @Override
    public Mono<Decision> review(final ControlRequest request) {
      return Mono.just(Decision.deny("no"));
    }
  }
}
