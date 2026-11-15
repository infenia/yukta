// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.AgentEndCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AgentInterruptCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AgentMessageCommand;
import com.infenia.yukta.service.agent.AgentSteering;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for the commands that steer an agent: a message, an interrupt and the end of a
 * conversation. {@link AgentSteering} checks who sent it and hands it to the live conversation. The
 * dispatcher has already ordered the command, let the supervisors review it and will audit it.
 */
@Component
@RequiredArgsConstructor
public class AgentCommandProcessor {

  /** Checks the sender and delivers the command; none when {@code yukta.agents.enabled} is off. */
  private final ObjectProvider<AgentSteering> steering;

  /** Applies a message. */
  public Mono<Void> process(final AgentMessageCommand command) {
    return steering().message(command.conversationId(), command.text(), command.interrupt());
  }

  /** Applies an interrupt. */
  public Mono<Void> process(final AgentInterruptCommand command) {
    return steering().interrupt(command.conversationId());
  }

  /** Applies the end of a conversation. */
  public Mono<Void> process(final AgentEndCommand command) {
    return steering().end(command.conversationId(), command.outcome(), command.reason());
  }

  private AgentSteering steering() {
    final AgentSteering found = steering.getIfAvailable();
    if (found == null) {
      throw new IllegalStateException("Agent conversations are off (yukta.agents.enabled)");
    }
    return found;
  }
}
