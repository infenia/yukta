// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class SkipNodeCommandProcessorTest {

  private final ExecutionControlRegistry registry = mock(ExecutionControlRegistry.class);
  private final SkipNodeCommandProcessor processor = new SkipNodeCommandProcessor(registry);
  private final ExecutionControl control = mock(ExecutionControl.class);
  private final PreparedWorkflow prepared = mock(PreparedWorkflow.class);
  private final AtomicBoolean flag = new AtomicBoolean();

  private void run(final Plugin plugin) {
    when(registry.findByExecutionId("e1")).thenReturn(Optional.of(control));
    when(control.nodeSkipFlags()).thenReturn(Map.of("n1", flag));
    when(control.prepared()).thenReturn(prepared);
    when(prepared.pluginCache()).thenReturn(Map.of("n1", plugin));
  }

  @Test
  void canProcess_skipNodeCommand_returnsTrue() {
    assertThat(processor.canProcess(new SkipNodeCommand("e1", "n1", true))).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    final ExecutionControlCommand command = new PauseWorkflowCommand("e1");

    assertThat(processor.canProcess(command)).isFalse();
  }

  @Test
  void processorNode_isSkippedAndUnskipped() {
    run(mock(ProcessorPlugin.class));

    StepVerifier.create(processor.process(new SkipNodeCommand("e1", "n1", true))).verifyComplete();
    assertThat(flag).isTrue();
    StepVerifier.create(processor.process(new SkipNodeCommand("e1", "n1", false))).verifyComplete();
    assertThat(flag).isFalse();
  }

  @Test
  void nonProcessorNode_isRejectedWithReason() {
    run(mock(Plugin.class));

    StepVerifier.create(processor.process(new SkipNodeCommand("e1", "n1", true)))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Only processor nodes can be skipped"))
        .verify();
    assertThat(flag).isFalse();
  }

  @Test
  void unknownExecutionOrNode_isNotFound() {
    when(registry.findByExecutionId("nope")).thenReturn(Optional.empty());
    StepVerifier.create(processor.process(new SkipNodeCommand("nope", "n1", true)))
        .expectError(NotFoundException.class)
        .verify();

    run(mock(ProcessorPlugin.class));
    StepVerifier.create(processor.process(new SkipNodeCommand("e1", "ghost", true)))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void priority_isTen() {
    assertThat(processor.getPriority()).isEqualTo(10);
  }
}
