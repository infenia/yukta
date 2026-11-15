// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A conversation with an AI agent: one row of the Agents tab. The text of the conversation is in
 * its events. The reason and the model are written by the agent or its harness: show them as
 * untrusted text.
 *
 * @param id the conversation id
 * @param executionId the run of the node
 * @param rootExecutionId the top of the run's tree of runs
 * @param sessionId the session of the run
 * @param workflowId the workflow of the run
 * @param nodeId the node
 * @param messageId the message the node works on
 * @param harness the preset that describes the agent
 * @param protocol how Yukta talks to the agent: JSONL, ACP or PTY
 * @param model the model, when known
 * @param capabilities what the conversation can do: message, steer, interrupt, ask, resume,
 *     terminal
 * @param state STARTING, RUNNING, WAITING, IDLE or ENDED
 * @param outcome how it ended: success, failure or incomplete; null until it ended
 * @param reason why it is in this state or ended
 * @param turn how many turns have finished
 * @param queued how many messages wait for the turn to end
 * @param steerAudience who may steer it: audience selectors
 * @param startedAt when it was opened
 * @param updatedAt when this record last changed
 * @param endedAt when it ended
 * @param seq the change sequence of the last change of this record
 */
@Schema(description = "A conversation with an AI agent")
public record ConversationResponse(
    @Schema(description = "The conversation id") String id,
    @Schema(description = "The run of the node") String executionId,
    @Schema(description = "The top of the run's tree of runs") String rootExecutionId,
    @Schema(description = "The session of the run") String sessionId,
    @Schema(description = "The workflow of the run") String workflowId,
    @Schema(description = "The node") String nodeId,
    @Schema(description = "The message the node works on") String messageId,
    @Schema(description = "The preset that describes the agent") String harness,
    @Schema(
            description = "How Yukta talks to the agent",
            allowableValues = {"JSONL", "ACP", "PTY"})
        String protocol,
    @Schema(description = "The model, when known", nullable = true) @Nullable String model,
    @Schema(description = "What the conversation can do") List<String> capabilities,
    @Schema(
            description = "Where the conversation is",
            allowableValues = {"STARTING", "RUNNING", "WAITING", "IDLE", "ENDED"})
        String state,
    @Schema(
            description = "How it ended; null until it ended",
            allowableValues = {"success", "failure", "incomplete"},
            nullable = true)
        @Nullable String outcome,
    @Schema(description = "Why it is in this state or ended; untrusted text", nullable = true)
        @Nullable String reason,
    @Schema(description = "How many turns have finished") int turn,
    @Schema(description = "How many messages wait for the turn to end") int queued,
    @Schema(description = "Who may steer it: audience selectors") List<String> steerAudience,
    @Schema(description = "When it was opened") Instant startedAt,
    @Schema(description = "When this record last changed") Instant updatedAt,
    @Schema(description = "When it ended", nullable = true) @Nullable Instant endedAt,
    @Schema(description = "The change sequence of the last change of this record") long seq) {

  /** Keeps immutable copies of the collections. */
  public ConversationResponse {
    capabilities = List.copyOf(capabilities);
    steerAudience = List.copyOf(steerAudience);
  }
}
