// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputIdentity;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a plugin says when it opens a conversation.
 *
 * @param who the run and node, read from the Reactor context the engine writes
 * @param messageId the message the node works on
 * @param harness the preset that describes the agent
 * @param protocol how Yukta talks to the agent
 * @param model the model, or null
 * @param capabilities what the conversation can do; each one must be known
 * @param steerAudience who may steer it
 * @since 0.1.0-alpha.4
 */
public record AgentConversationSpec(
    InputIdentity who,
    String messageId,
    String harness,
    AgentProtocol protocol,
    @Nullable String model,
    Set<String> capabilities,
    Audience steerAudience) {

  /**
   * Checks the parts and keeps an immutable copy of the capabilities.
   *
   * @param who the run and node
   * @param messageId the message
   * @param harness the preset
   * @param protocol the protocol
   * @param model the model, or null
   * @param capabilities the capabilities
   * @param steerAudience who may steer
   */
  public AgentConversationSpec {
    Objects.requireNonNull(who, "who");
    requireText("messageId", messageId);
    requireText("harness", harness);
    Objects.requireNonNull(protocol, "protocol");
    capabilities = AgentCapability.requireKnown(capabilities);
    Objects.requireNonNull(steerAudience, "steerAudience");
  }

  private static void requireText(final String name, final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
