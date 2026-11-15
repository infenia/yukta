// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.AgentEndCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AgentInterruptCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AgentMessageCommand;
import com.infenia.yukta.service.agent.AgentSteering;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The processor hands each agent command to the steering with its parts. */
@NoArgsConstructor
class AgentCommandProcessorTest {

  private final AgentSteering steering = mock(AgentSteering.class);
  private final AgentCommandProcessor processor = new AgentCommandProcessor(steering);

  @Test
  void whenMessageIsSentWithItsInterruptFlag() {
    when(steering.message("c-1", "hi", true)).thenReturn(Mono.empty());

    StepVerifier.create(processor.process(new AgentMessageCommand("e", "n", "c-1", "hi", true)))
        .verifyComplete();

    verify(steering).message("c-1", "hi", true);
  }

  @Test
  void anInterruptIsSent() {
    when(steering.interrupt("c-1")).thenReturn(Mono.empty());

    StepVerifier.create(processor.process(new AgentInterruptCommand("e", "n", "c-1")))
        .verifyComplete();

    verify(steering).interrupt("c-1");
  }

  @Test
  void anEndIsSentWithItsOutcomeAndReason() {
    when(steering.end("c-1", "failure", "why")).thenReturn(Mono.empty());

    StepVerifier.create(processor.process(new AgentEndCommand("e", "n", "c-1", "failure", "why")))
        .verifyComplete();

    verify(steering).end("c-1", "failure", "why");
  }
}
