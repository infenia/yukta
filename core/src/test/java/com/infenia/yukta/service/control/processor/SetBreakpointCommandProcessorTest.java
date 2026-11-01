// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetBreakpointCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SetBreakpointCommandProcessorTest {

  private final ExecutionControlStore registry = mock(ExecutionControlStore.class);
  private final TaskTrackerService taskTracker = mock(TaskTrackerService.class);
  private final SetBreakpointCommandProcessor processor =
      new SetBreakpointCommandProcessor(registry, taskTracker);
  private final ExecutionControl control = mock(ExecutionControl.class);
  private final ReactiveControlValve valve = new ReactiveControlValve();

  private Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  private void set(final String field, final String value) {
    when(registry.findByExecutionId("e1")).thenReturn(Optional.of(control));
    when(control.nodePauseValves()).thenReturn(Map.of("n1", valve));
    StepVerifier.create(processor.process(new SetBreakpointCommand("e1", "n1", field, value)))
        .verifyComplete();
  }

  private boolean holds(final Message<?> message) {
    valve.resume();
    valve.allowPassage(message).subscribe();
    final boolean paused = valve.isPaused();
    valve.resume();
    return paused;
  }

  @Test
  void canProcess_onlyItsOwnCommand() {
    assertThat(processor.canProcess(new SetBreakpointCommand("e", "n", null, null))).isTrue();
    assertThat(processor.canProcess(new PauseWorkflowCommand("e"))).isFalse();
  }

  @Test
  void payloadPath_holdsNodeOnMatchingElementOnly() {
    set("payload.order.id", "42");

    assertThat(holds(message(Map.of("order", Map.of("id", 42))))).isTrue();
    assertThat(holds(message(Map.of("order", Map.of("id", 7))))).isFalse();
    assertThat(holds(message(Map.of("other", 1)))).isFalse();
    assertThat(holds(message("not a map"))).isFalse();
  }

  @Test
  void wholePayload_andMetadata_canBeMatched() {
    set("payload", "hello");
    assertThat(holds(message("hello"))).isTrue();

    set("metadata.kind", "poison");
    assertThat(holds(message("x").withHeader("kind", "poison"))).isTrue();
    assertThat(holds(message("x"))).isFalse();
  }

  @Test
  void commandWithoutField_clearsTheBreakpoint() {
    set("payload", "hello");
    set(null, null);

    assertThat(holds(message("hello"))).isFalse();
  }

  @Test
  void unknownExecutionOrNode_isNotFound() {
    when(registry.findByExecutionId("nope")).thenReturn(Optional.empty());
    StepVerifier.create(processor.process(new SetBreakpointCommand("nope", "n1", "payload", "x")))
        .expectError(NotFoundException.class)
        .verify();

    when(registry.findByExecutionId("e1")).thenReturn(Optional.of(control));
    when(control.nodePauseValves()).thenReturn(Map.of());
    StepVerifier.create(processor.process(new SetBreakpointCommand("e1", "n1", "payload", "x")))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void priority_isTen() {
    assertThat(processor.getPriority()).isEqualTo(10);
  }

  @Test
  void process_reportsTheBreakpointToTheTrackerAndNullWhenCleared() {
    set("payload.kind", "error");
    set(null, null);

    org.mockito.Mockito.verify(taskTracker)
        .emitBreakpointEvent("e1", "n1", new TaskProgress.Breakpoint("payload.kind", "error"));
    org.mockito.Mockito.verify(taskTracker).emitBreakpointEvent("e1", "n1", null);
  }
}
