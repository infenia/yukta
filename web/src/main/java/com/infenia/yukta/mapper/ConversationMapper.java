// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import com.infenia.yukta.dto.response.ConversationEventResponse;
import com.infenia.yukta.dto.response.ConversationResponse;
import com.infenia.yukta.dto.response.TerminalScreenResponse;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper from the conversation records of the plugin API to the REST responses. */
@Mapper(componentModel = "spring")
public interface ConversationMapper {

  /**
   * Map a conversation.
   *
   * @param conversation the conversation
   * @return the response, with the capabilities sorted
   */
  @Mapping(
      target = "capabilities",
      expression = "java(conversation.capabilities().stream().sorted().toList())")
  @Mapping(target = "steerAudience", source = "steerAudience.selectors")
  ConversationResponse toResponse(AgentConversation conversation);

  /**
   * Map an event.
   *
   * @param event the event
   * @return the response
   */
  default ConversationEventResponse toResponse(final AgentEvent event) {
    return new ConversationEventResponse(
        event.seq(),
        event.conversationId(),
        event.timestamp(),
        event.kind().name(),
        event.itemId(),
        event.data());
  }

  /**
   * Map a {@code TERMINAL} event that carries a screen.
   *
   * @param event the event
   * @return the screen; a number the event lacks is 0 and the text empty
   */
  default TerminalScreenResponse toScreen(final AgentEvent event) {
    return new TerminalScreenResponse(
        number(event, "cols"),
        number(event, "rows"),
        String.valueOf(event.data().getOrDefault("screen", "")),
        number(event, "cursorRow"),
        number(event, "cursorCol"),
        event.seq());
  }

  private static int number(final AgentEvent event, final String key) {
    return event.data().get(key) instanceof final Number value ? value.intValue() : 0;
  }
}
