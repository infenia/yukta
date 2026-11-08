// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.service.archive.ExecutionArchiveFixture;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.runtime.ExecutionControl;
import com.infenia.yukta.service.runtime.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.review.ControlReviewerFixture;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** The channel a command arrived on reaches the audit record, the log and the outcome event. */
@NoArgsConstructor
class DirectiveDispatcherChannelTest {

  private static final String OPERATOR = ControlActor.Channel.OPERATOR.name();
  private static final String ENGINE = ControlActor.Channel.ENGINE.name();
  private static final String SYSTEM = ControlActor.Channel.SYSTEM.name();

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
  private final ExecutionEventFeed feed = new ExecutionEventFeed();
  private final CommandHandler handler = mock(CommandHandler.class);

  private DirectiveDispatcher dispatcher(final List<AuditRecord> sunk) {
    final InMemoryExecutionControlStore registry = new InMemoryExecutionControlStore();
    registry.save(
        new ExecutionControl(
            "s",
            "w",
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
    final DirectiveDispatcher dispatcher =
        new DirectiveDispatcher(
            handler,
            registry,
            mock(ControlBusService.class),
            ExecutionArchiveFixture.create(),
            log,
            ControlReviewerFixture.create(
                new StaticListableBeanFactory().getBeanProvider(ExecutionSupervisor.class)),
            feed);
    dispatcher.setAuditSink(sunk::add);
    return dispatcher;
  }

  @Test
  void operatorEngineAndSystemAreRecordedOnTheAuditRecordTheLogAndTheOutcome() {
    final List<AuditRecord> sunk = new ArrayList<>();
    final List<ExecutionEvent> events = new ArrayList<>();
    feed.events().subscribe(events::add);
    final DirectiveDispatcher dispatcher = dispatcher(sunk);
    final var pause = new ExecutionControlCommand.PauseWorkflowCommand("e1");
    when(handler.handle(pause)).thenReturn(Mono.empty());

    StepVerifier.create(
            dispatcher
                .dispatchOrdered(pause)
                .contextWrite(Context.of(ControlActor.CHANNEL_KEY, OPERATOR)))
        .verifyComplete();
    StepVerifier.create(
            dispatcher
                .dispatchOrdered(pause)
                .contextWrite(Context.of(ControlActor.CHANNEL_KEY, ENGINE)))
        .verifyComplete();
    StepVerifier.create(dispatcher.dispatchOrdered(pause)).verifyComplete();

    assertThat(sunk).extracting(AuditRecord::channel).containsExactly(OPERATOR, ENGINE, SYSTEM);
    assertThat(log.readAll())
        .extracting(e -> Objects.requireNonNull(e.payload()).get(ExecutionLogEvent.CHANNEL_KEY))
        .containsExactly(OPERATOR, ENGINE, SYSTEM);
    org.awaitility.Awaitility.await()
        .untilAsserted(
            () ->
                assertThat(events)
                    .filteredOn(ExecutionEvent.ControlOutcome.class::isInstance)
                    .extracting(e -> ((ExecutionEvent.ControlOutcome) e).channel())
                    .containsExactly(OPERATOR, ENGINE, SYSTEM));
  }

  @Test
  void startHasTheWorkflowAsItsTarget() {
    final var start = new ExecutionControlCommand.StartWorkflowCommand("s", "w", Map.of(), "key");
    StepVerifier.create(dispatcher(new ArrayList<>()).target(start))
        .assertNext(
            t -> {
              assertThat(t.sessionId()).isEqualTo("s");
              assertThat(t.workflowId()).isEqualTo("w");
              assertThat(t.nodeId()).isNull();
            })
        .verifyComplete();
    assertThat(start.executionId()).isEqualTo(ExecutionControlCommand.START);
  }

  @Test
  void startIsNotQueuedOnRun() {
    final var start = new ExecutionControlCommand.StartWorkflowCommand("s", "w", Map.of(), null);
    StepVerifier.create(dispatcher(new ArrayList<>()).dispatchOrdered(start))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
