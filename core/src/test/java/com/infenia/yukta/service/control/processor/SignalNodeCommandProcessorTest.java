// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.test.StepVerifier;

/** The signal command without a bridge: waits are input requests, so nothing else can take it. */
@NoArgsConstructor
class SignalNodeCommandProcessorTest {

  private final ExecutionControlStore registry = mock(ExecutionControlStore.class);
  private final SignalNodeCommandProcessor processor = new SignalNodeCommandProcessor(registry);
  private final SignalNodeCommand command =
      new SignalNodeCommand("e1", "n1", "approval", "approve", "fine");

  @Test
  void unknownExecution_isNotFound() {
    when(registry.findByExecutionId("e1")).thenReturn(Optional.empty());
    StepVerifier.create(processor.process(command)).verifyError(NotFoundException.class);
  }

  @Test
  void withoutBridge_noNodeIsWaiting() {
    when(registry.findByExecutionId("e1")).thenReturn(Optional.of(mock(ExecutionControl.class)));
    StepVerifier.create(processor.process(command))
        .verifyErrorSatisfies(
            e ->
                org.assertj.core.api.Assertions.assertThat(e)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("answer_input"));
  }

  @Test
  void withBridgeProviderThatHasNone_noNodeIsWaiting() {
    final ObjectProvider<InputSignalBridge> none = mock(ObjectProvider.class);
    processor.setInputBridge(none);
    when(registry.findByExecutionId("e1")).thenReturn(Optional.of(mock(ExecutionControl.class)));
    StepVerifier.create(processor.process(command)).verifyError(ConflictException.class);
  }
}
