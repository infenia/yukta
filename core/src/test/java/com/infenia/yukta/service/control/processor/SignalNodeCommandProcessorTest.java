// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
class SignalNodeCommandProcessorTest {

  private final ExecutionControlRegistry registry = mock(ExecutionControlRegistry.class);
  private final ActivePluginRegistry plugins = mock(ActivePluginRegistry.class);
  private final SignalNodeCommandProcessor processor =
      new SignalNodeCommandProcessor(registry, plugins);
  private final ExecutionControl control = mock(ExecutionControl.class);
  private final Plugin plugin = mock(Plugin.class);
  private final SignalNodeCommand command =
      new SignalNodeCommand("e1", "n1", "approval", "approve", "fine");

  private void known() {
    when(registry.findByExecutionId("e1")).thenReturn(Optional.of(control));
    when(control.workflowId()).thenReturn("wf");
    when(plugins.lookup("wf", "e1", "n1")).thenReturn(Optional.of(plugin));
  }

  @Test
  void canProcess_onlyItsOwnCommand() {
    assertThat(processor.canProcess(command)).isTrue();
    assertThat(processor.canProcess(new PauseWorkflowCommand("e1"))).isFalse();
  }

  @Test
  void acceptedSignal_reachesThePluginWithTheDecision() {
    known();
    final AtomicReference<Message<?>> seen = new AtomicReference<>();
    when(plugin.onControlSignal(any()))
        .thenAnswer(
            call -> {
              seen.set(call.getArgument(0));
              return Mono.just(DefaultMessage.create(UUID.randomUUID(), "ACCEPTED"));
            });

    StepVerifier.create(processor.process(command)).verifyComplete();

    assertThat(seen.get().payload())
        .isEqualTo(
            Map.of(
                "executionId", "e1",
                "nodeId", "n1",
                "name", "approval",
                "decision", "approve",
                "data", "fine"));
  }

  @Test
  void signalNobodyWaitsFor_isConflict() {
    known();
    when(plugin.onControlSignal(any()))
        .thenReturn(Mono.just(DefaultMessage.create(UUID.randomUUID(), "NOT_WAITING")));
    StepVerifier.create(processor.process(command)).verifyError(ConflictException.class);

    when(plugin.onControlSignal(any())).thenReturn(Mono.empty());
    StepVerifier.create(processor.process(command)).verifyError(ConflictException.class);
  }

  @Test
  void unknownExecutionOrNode_isNotFound() {
    when(registry.findByExecutionId("e1")).thenReturn(Optional.empty());
    StepVerifier.create(processor.process(command)).verifyError(NotFoundException.class);

    when(registry.findByExecutionId("e1")).thenReturn(Optional.of(control));
    when(control.workflowId()).thenReturn("wf");
    when(plugins.lookup("wf", "e1", "n1")).thenReturn(Optional.empty());
    StepVerifier.create(processor.process(command)).verifyError(NotFoundException.class);
  }

  @Test
  void signalKeptForWaitThatHasNotBegun_isAccepted() {
    known();
    when(plugin.onControlSignal(any()))
        .thenReturn(Mono.just(DefaultMessage.create(UUID.randomUUID(), "QUEUED")));

    StepVerifier.create(processor.process(command)).verifyComplete();
  }
}
