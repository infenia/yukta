// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import com.infenia.yukta.plugin.input.Audience;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * One conversation with an agent: what a client sees as a row in a list. The text of the
 * conversation is in its {@link AgentEvent}s.
 *
 * @param id the conversation id (a UUID)
 * @param executionId the run of the node
 * @param rootExecutionId the top of the run's tree of runs; the run itself when it has no parent
 * @param sessionId the session of the run
 * @param workflowId the workflow of the run
 * @param nodeId the node
 * @param messageId the message the node works on
 * @param harness the preset that describes the agent
 * @param protocol how Yukta talks to the agent
 * @param model the model, when the agent or the node names one
 * @param capabilities what the conversation can do, see {@link AgentCapability}
 * @param state where the conversation is
 * @param outcome how it ended; null until {@link AgentState#ENDED}
 * @param reason why it is in this state or ended; null when there is nothing to say
 * @param turn how many turns have finished
 * @param queued how many messages wait for the turn to end
 * @param steerAudience who may send a message, interrupt or end it
 * @param startedAt when it was opened
 * @param updatedAt when this record last changed (an event does not change it)
 * @param endedAt when it ended; null until then
 * @param seq the change sequence of the last change of this record
 * @since 0.1.0-alpha.4
 */
@With
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "capabilities is an immutable copy made in the constructor")
public record AgentConversation(
    String id,
    String executionId,
    String rootExecutionId,
    String sessionId,
    String workflowId,
    String nodeId,
    String messageId,
    String harness,
    AgentProtocol protocol,
    @Nullable String model,
    Set<String> capabilities,
    AgentState state,
    @Nullable String outcome,
    @Nullable String reason,
    int turn,
    int queued,
    Audience steerAudience,
    Instant startedAt,
    Instant updatedAt,
    @Nullable Instant endedAt,
    long seq) {

  /**
   * Checks the parts and keeps an immutable copy of the capabilities.
   *
   * @param id the conversation id
   * @param executionId the run
   * @param rootExecutionId the root run
   * @param sessionId the session
   * @param workflowId the workflow
   * @param nodeId the node
   * @param messageId the message
   * @param harness the preset
   * @param protocol the protocol
   * @param model the model, or null
   * @param capabilities the capabilities; each one must be known
   * @param state the state
   * @param outcome the outcome, or null
   * @param reason the reason, or null
   * @param turn finished turns, not negative
   * @param queued waiting messages, not negative
   * @param steerAudience who may steer
   * @param startedAt when it was opened
   * @param updatedAt when it last changed
   * @param endedAt when it ended, or null
   * @param seq the change sequence
   */
  public AgentConversation {
    requireText("id", id);
    requireText("executionId", executionId);
    requireText("rootExecutionId", rootExecutionId);
    requireText("sessionId", sessionId);
    requireText("workflowId", workflowId);
    requireText("nodeId", nodeId);
    requireText("messageId", messageId);
    requireText("harness", harness);
    Objects.requireNonNull(protocol, "protocol");
    capabilities = AgentCapability.requireKnown(capabilities);
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(steerAudience, "steerAudience");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (turn < 0 || queued < 0) {
      throw new IllegalArgumentException("turn and queued must not be negative");
    }
    if (outcome != null) {
      AgentOutcome.require(outcome);
    }
  }

  private static void requireText(final String name, final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  /**
   * Tells whether the conversation has ended.
   *
   * @return true in state {@link AgentState#ENDED}
   */
  public boolean isEnded() {
    return state == AgentState.ENDED;
  }
}
