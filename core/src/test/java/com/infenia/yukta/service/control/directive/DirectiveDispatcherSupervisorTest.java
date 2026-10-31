// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.ExcessiveImports", "PMD.TooManyStaticImports"})
@NoArgsConstructor
class DirectiveDispatcherSupervisorTest {

  private static final ExecutionControlCommand PAUSE =
      new ExecutionControlCommand.PauseWorkflowCommand("e1");

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
  private final ExecutionEventFeed feed = new ExecutionEventFeed();
  private final List<ExecutionEvent> events = new ArrayList<>();
  private ControlSignalProcessor processor;
  private ExecutionSupervisor supervisor;
  private DirectiveDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    final ExecutionControlRegistry registry =
        new ExecutionControlRegistry(new InMemoryExecutionControlStore());
    registry.register(
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
    processor = mock(ControlSignalProcessor.class);
    when(processor.canProcess(any())).thenReturn(true);
    when(processor.process(any())).thenReturn(Mono.empty());
    supervisor = mock(ExecutionSupervisor.class);
    when(supervisor.scope())
        .thenReturn(new com.infenia.yukta.plugin.control.SupervisorScope.Global());
    when(supervisor.id()).thenReturn("guard");
    when(supervisor.review(any())).thenReturn(Mono.just(Decision.ALLOW));
    dispatcher =
        new DirectiveDispatcher(
            List.of(processor),
            registry,
            mock(ControlBusService.class),
            new ExecutionArchive(),
            log,
            new ControlReviewer(providerOf(supervisor)),
            feed);
    feed.events().subscribe(events::add);
  }

  @Test
  void dispatchOrdered_denied_commandNotAppliedAndRejectionRecorded() {
    when(supervisor.review(any())).thenReturn(Mono.just(Decision.deny("maintenance window")));

    StepVerifier.create(dispatcher.dispatchOrdered(PAUSE))
        .expectError(ControlRejectedException.class)
        .verify();

    verify(processor, never()).process(any());
    assertThat(log.readAll())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
              assertThat(entry.status()).contains("guard: maintenance window");
            });
    assertThat(events)
        .singleElement()
        .isInstanceOfSatisfying(
            ExecutionEvent.ControlOutcome.class,
            outcome -> assertThat(outcome.applied()).isFalse());
  }

  @Test
  void dispatchOrdered_withActorInContext_actorReviewedRecordedAndPublished() {
    StepVerifier.create(
            dispatcher
                .dispatchOrdered(PAUSE)
                .contextWrite(reactor.util.context.Context.of(ControlActor.CONTEXT_KEY, "rules")))
        .verifyComplete();

    verify(supervisor)
        .review(
            org.mockito.ArgumentMatchers.argThat(
                request -> "rules".equals(request.actor()) && request.command() == PAUSE));
    assertThat(log.readAll())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_APPLIED);
              assertThat(entry.status()).isEqualTo("PauseWorkflowCommand [by rules]");
            });
    assertThat(events)
        .singleElement()
        .isInstanceOfSatisfying(
            ExecutionEvent.ControlOutcome.class,
            outcome -> {
              assertThat(outcome.applied()).isTrue();
              assertThat(outcome.actor()).isEqualTo("rules");
            });
  }

  @Test
  void dispatchOrdered_noActor_recordedWithoutActor() {
    StepVerifier.create(dispatcher.dispatchOrdered(PAUSE)).verifyComplete();

    assertThat(log.readAll())
        .singleElement()
        .satisfies(e -> assertThat(e.status()).isEqualTo("PauseWorkflowCommand"));
    verify(supervisor).review(org.mockito.ArgumentMatchers.argThat(r -> r.actor() == null));
    assertThat(Flux.fromIterable(events).count().block()).isEqualTo(1L);
  }
}
