// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.service.control.directive.CommandTarget;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import com.infenia.yukta.service.supervisor.SupervisorStore.Hold;
import com.infenia.yukta.service.supervisor.engine.SupervisorQueries;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The hold ledger kept in the store: written behind the host and loaded after a restart. */
class SupervisorHostHoldsTest {

  private final ExecutionEventFeed feed = new ExecutionEventFeed();
  private final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);
  private SupervisorHost host;

  @AfterEach
  void tearDown() {
    host.stop();
  }

  private SupervisorHost host(final ExecutionSupervisor supervisor) {
    host =
        SupervisorHostFixture.create(
            providerOf(supervisor),
            feed,
            dispatcher,
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            false,
            100,
            10_000);
    return host;
  }

  private static ExecutionSupervisor pausingOnFailure() {
    return new ExecutionSupervisor() {
      @Override
      public String id() {
        return "rules";
      }

      @Override
      public Flux<com.infenia.yukta.plugin.control.ExecutionControlCommand> supervise(
          final Flux<ExecutionEvent> events) {
        return events
            .ofType(ExecutionEvent.NodeStatusChanged.class)
            .filter(e -> "FAILURE".equals(e.status()))
            .map(e -> new PauseWorkflowCommand(e.executionId()));
      }
    };
  }

  private static ExecutionEvent failed(final String execution) {
    return new ExecutionEvent.NodeStatusChanged(
        execution, "s", "wf", "n", "echo", "FAILURE", null, Instant.now());
  }

  @Test
  void changesAreWrittenInOrderAndFailedWriteDoesNotStopTheNext() {
    final AtomicBoolean failedOnce = new AtomicBoolean();
    final InMemorySupervisorStore store =
        new InMemorySupervisorStore() {
          @Override
          public Mono<Void> saveHold(final Hold hold) {
            return failedOnce.compareAndSet(false, true)
                ? Mono.error(new IllegalStateException("database down"))
                : super.saveHold(hold);
          }
        };
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    host(pausingOnFailure()).keepHoldsIn(store);
    host.start();

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              feed.publish(failed("e1"));
              assertThat(store.holds().collectList().block())
                  .containsExactly(new Hold("rules", "e1"));
            });
    assertThat(failedOnce).isTrue();

    feed.publish(
        new ExecutionEvent.WorkflowStatusChanged("e1", "s", "wf", "SUCCESS", Instant.now()));
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(store.holds().collectList().block()).isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void loadKeepsTheRunsThatGoOnAndForgetsTheOthers() {
    final InMemorySupervisorStore store = new InMemorySupervisorStore();
    store.saveHold(new Hold("s", "going")).block();
    store.saveHold(new Hold("s", "ended")).block();
    store.saveHold(new Hold("s", "unknown")).block();
    when(dispatcher.target(new PauseWorkflowCommand("going")))
        .thenReturn(Mono.just(new CommandTarget("sess", "wf", null)));
    when(dispatcher.target(new PauseWorkflowCommand("ended")))
        .thenReturn(Mono.just(new CommandTarget("sess", "wf", null)));
    when(dispatcher.target(new PauseWorkflowCommand("unknown"))).thenReturn(Mono.empty());
    final SupervisorQueries queries = mock(SupervisorQueries.class);
    when(queries.statusOf("sess", "going")).thenReturn(Mono.just("RUNNING"));
    when(queries.statusOf("sess", "ended")).thenReturn(Mono.just("SUCCESS"));
    final ObjectProvider<SupervisorQueries> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(queries);
    host(pausingOnFailure()).setQueries(provider);

    host.loadHolds(store).block();

    assertThat(host.held("s")).containsExactly("going");
    assertThat(store.holds().collectList().block()).containsExactly(new Hold("s", "going"));
  }

  @Test
  void withoutQueriesEveryKeptHoldIsLoaded() {
    final InMemorySupervisorStore store = new InMemorySupervisorStore();
    store.saveHold(new Hold("s", "a")).block();

    host(pausingOnFailure()).loadHolds(store).block();

    assertThat(host.held("s")).containsExactly("a");
  }
}
