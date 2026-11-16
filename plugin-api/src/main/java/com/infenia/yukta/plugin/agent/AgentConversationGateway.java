// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import reactor.core.publisher.Mono;

/**
 * Lets a plugin record what an agent does and listen for what clients send it.
 *
 * <p>The engine provides one implementation as a Spring bean; a plugin takes it through an {@code
 * ObjectProvider}, the way it takes the {@code InputGateway}. Without the bean the plugin uses
 * {@link AgentConversationHandle#none()} and behaves as it did before conversations existed.
 *
 * @since 0.1.0-alpha.4
 */
@FunctionalInterface
public interface AgentConversationGateway {

  /**
   * Opens a conversation in state {@link AgentState#STARTING}.
   *
   * @param spec what the plugin knows about it
   * @return the handle the plugin writes through; it fails if the conversation cannot be stored
   */
  Mono<AgentConversationHandle> open(AgentConversationSpec spec);
}
