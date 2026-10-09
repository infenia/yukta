// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.LinguisticNaming"})
class SkipNodeCommandProcessorTest {

  private final SkipNodeCommandProcessor processor = new SkipNodeCommandProcessor();

  @Test
  void canProcess_skipNodeCommand_returnsTrue() {
    final ExecutionControlCommand command = new SkipNodeCommand("exec-1", "node-1", true);

    assertThat(processor.canProcess(command)).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    final ExecutionControlCommand command = new PauseWorkflowCommand("exec-1");

    assertThat(processor.canProcess(command)).isFalse();
  }

  @Test
  void process_rejectsSkipOfRunningExecution() {
    final ExecutionControlCommand command = new SkipNodeCommand("exec-1", "node-1", true);

    StepVerifier.create(processor.process(command))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("not supported")
                    && e.getMessage().contains("node-1"))
        .verify();
  }

  @Test
  void getPriority_returnsCorrectValue() {
    assertThat(processor.getPriority()).isEqualTo(10);
  }
}
