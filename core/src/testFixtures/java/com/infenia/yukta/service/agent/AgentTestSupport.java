// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/** Builds the values the agent conversation tests need. */
@UtilityClass
public class AgentTestSupport {

  /** The time of everything unless a test says otherwise. */
  public static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

  /**
   * A live conversation in state RUNNING, started at {@link #NOW}.
   *
   * @param id the conversation id
   * @return the conversation
   */
  public static AgentConversation conversation(final String id) {
    return new AgentConversation(
        id,
        "exec-1",
        "root-1",
        "session-1",
        "workflow-1",
        "node-1",
        "message-1",
        "claude-code",
        AgentProtocol.JSONL,
        null,
        Set.of(AgentCapability.MESSAGE),
        AgentState.RUNNING,
        null,
        null,
        0,
        0,
        Audience.CURRENT_TIER,
        NOW,
        NOW,
        null,
        0);
  }

  /**
   * An ended conversation with the outcome {@code success}.
   *
   * @param id the conversation id
   * @param endedAt when it ended
   * @return the conversation
   */
  public static AgentConversation ended(final String id, final Instant endedAt) {
    return conversation(id).withOutcome("success").withEndedAt(endedAt).withState(AgentState.ENDED);
  }

  /**
   * An event of a kind with the data {@code text: x}.
   *
   * @param conversationId the conversation
   * @param kind the kind
   * @return the event, not yet numbered
   */
  public static AgentEvent event(final String conversationId, final AgentEventKind kind) {
    return new AgentEvent(0, conversationId, NOW, kind, null, Map.of("text", "x"));
  }

  /**
   * A piece of terminal output.
   *
   * @param conversationId the conversation
   * @param base64 the bytes, in base64
   * @return the event, not yet numbered
   */
  public static AgentEvent output(final String conversationId, final String base64) {
    return new AgentEvent(
        0,
        conversationId,
        NOW,
        AgentEventKind.TERMINAL,
        null,
        Map.of("data", base64, "cols", 80, "rows", 24));
  }

  /**
   * A settled terminal screen.
   *
   * @param conversationId the conversation
   * @param text the text of the screen
   * @return the event, not yet numbered
   */
  public static AgentEvent screen(final String conversationId, final String text) {
    return new AgentEvent(
        0,
        conversationId,
        NOW,
        AgentEventKind.TERMINAL,
        null,
        Map.of(
            "screen",
            text,
            "cols",
            80,
            "rows",
            24,
            "cursorRow",
            0,
            "cursorCol",
            0,
            "settled",
            true));
  }

  /**
   * A spec for node {@code node-1} of run {@code exec-1}.
   *
   * @param capabilities what the conversation can do
   * @return the spec
   */
  public static AgentConversationSpec spec(final String... capabilities) {
    return new AgentConversationSpec(
        new InputIdentity("session-1", "workflow-1", "exec-1", "node-1"),
        "message-1",
        "claude-code",
        AgentProtocol.JSONL,
        "haiku",
        Set.of(capabilities),
        Audience.CURRENT_TIER);
  }

  /**
   * The agent settings with the given limits and the defaults for the rest.
   *
   * @param maxMessageBytes the longest text of an event
   * @param maxToolBytes the longest tool input or output
   * @param maxEvents the most events per conversation
   * @return the settings
   */
  public static YuktaProperties.Agents settings(
      final int maxMessageBytes, final int maxToolBytes, final int maxEvents) {
    return new YuktaProperties.Agents(
        true,
        Duration.ofDays(7),
        500,
        maxEvents,
        maxMessageBytes,
        maxToolBytes,
        262_144,
        30,
        new YuktaProperties.Agents.Pty(false, "yukta-cli", 120, 40));
  }
}
