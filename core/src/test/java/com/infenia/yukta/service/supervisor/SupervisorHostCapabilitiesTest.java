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
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/** A supervisor sends only the commands its capabilities allow, and is known as a supervisor. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports"
})
class SupervisorHostCapabilitiesTest {

  private final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private final SupervisorHost host =
      new SupervisorHost(
          providerOf(), new ExecutionEventFeed(), dispatcher, log, meters, false, 20, 100);

  @AfterEach
  void tearDown() {
    host.stop();
  }

  private static ExecutionSupervisor emitting(final ExecutionControlCommand... commands) {
    return new ExecutionSupervisor() {
      @Override
      @SuppressWarnings("PMD.ShortMethodName")
      public String id() {
        return "unused";
      }

      @Override
      public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
        return Flux.just(commands).concatWith(events.then(Mono.empty()));
      }
    };
  }

  private void attach(
      final SupervisorCapabilities capabilities, final ExecutionControlCommand... commands) {
    host.attach(
        new BoundSupervisor(
            new SupervisorBinding("guard", "p", new SupervisorScope.Global(), true),
            emitting(commands),
            false,
            capabilities));
  }

  private long count(final String outcome) {
    final var counter =
        meters.find("yukta.supervisor.commands").tags("id", "guard", "outcome", outcome).counter();
    return counter == null ? 0 : (long) counter.count();
  }

  @Test
  void commandOutsideAllowedCommandsIsDroppedCountedAndRecorded() {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    attach(
        new SupervisorCapabilities(Set.of("PauseWorkflowCommand"), false, InputRisk.LOW),
        new StopWorkflowCommand("e1", "no", null),
        new PauseWorkflowCommand("e2"));

    await().atMost(3, TimeUnit.SECONDS).until(() -> count("applied") == 1);

    assertThat(count("forbidden")).isEqualTo(1);
    verify(dispatcher, never()).dispatchOrdered(new StopWorkflowCommand("e1", "no", null));
    verify(dispatcher).dispatchOrdered(new PauseWorkflowCommand("e2"));
    final List<SupervisorDecision> decisions = host.decisions("guard", 10);
    assertThat(decisions)
        .anySatisfy(
            decision -> {
              assertThat(decision.outcome()).isEqualTo("forbidden");
              assertThat(decision.command()).isEqualTo("StopWorkflowCommand");
              assertThat(decision.reason()).contains("not in allowedCommands");
            });
  }

  @Test
  void supervisorWithoutLimitSendsEveryCommand() {
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    attach(
        new SupervisorCapabilities(null, false, InputRisk.LOW),
        new StopWorkflowCommand("e1", "no", null),
        new PauseWorkflowCommand("e2"));

    await().atMost(3, TimeUnit.SECONDS).until(() -> count("applied") == 2);

    assertThat(count("forbidden")).isZero();
  }

  @Test
  void supervisorsCommandsCarryItsIdAndTheSupervisorKind() {
    final AtomicReference<ContextView> seen = new AtomicReference<>();
    when(dispatcher.dispatchOrdered(any()))
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  seen.set(context);
                  return Mono.empty();
                }));
    attach(new SupervisorCapabilities(null, false, InputRisk.LOW), new PauseWorkflowCommand("e1"));

    await().atMost(3, TimeUnit.SECONDS).until(() -> seen.get() != null);

    assertThat(seen.get().<String>get(ControlActor.CONTEXT_KEY)).isEqualTo("guard");
    assertThat(seen.get().<ControlActor.Kind>get(ControlActor.KIND_KEY))
        .isEqualTo(ControlActor.Kind.SUPERVISOR);
  }

  @Test
  void boundSupervisorMadeWithoutCapabilitiesMaySendAnythingButNeverAnswers() {
    final BoundSupervisor bound =
        new BoundSupervisor(
            new SupervisorBinding("guard", "p", new SupervisorScope.Global(), true),
            emitting(),
            false);

    assertThat(bound.capabilities().allowedCommands()).isNull();
    assertThat(bound.capabilities().canAnswer()).isFalse();
  }
}
