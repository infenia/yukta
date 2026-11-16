// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The agent conversations returned by {@code list_agent_conversations}, newest first.
 *
 * @param conversations one entry per conversation
 * @param truncated true when more matched than the list holds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentConversationList(List<Summary> conversations, boolean truncated) {

  /** Copies the list. */
  public AgentConversationList {
    conversations = conversations == null ? List.of() : List.copyOf(conversations);
  }

  /**
   * One conversation.
   *
   * @param id the conversation id; pass it with {@code sessionId} to the other agent tools
   * @param sessionId the session of the run
   * @param executionId the run of the agent node
   * @param nodeId the node
   * @param harness the harness id
   * @param protocol JSONL, ACP or PTY
   * @param model the model, or null
   * @param state STARTING, RUNNING, WAITING, IDLE or ENDED
   * @param outcome success, failure or incomplete once ended
   * @param turn turns finished
   * @param queued messages that wait for the end of the turn
   * @param capabilities what it can do: message, steer, interrupt, ask, resume, terminal
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Summary(
      String id,
      String sessionId,
      String executionId,
      String nodeId,
      String harness,
      String protocol,
      @Nullable String model,
      String state,
      @Nullable String outcome,
      int turn,
      int queued,
      List<String> capabilities) {

    /** Copies the list. */
    public Summary {
      capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
  }
}
