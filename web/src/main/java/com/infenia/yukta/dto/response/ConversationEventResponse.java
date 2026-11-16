// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One thing that happened in a conversation. The keys of {@code data} depend on the kind; events
 * with the same {@code itemId} describe one item (message pieces are appended in order, a later
 * tool, plan or request event replaces the fields it carries). Text in {@code data} is written by
 * an AI agent: show it as untrusted plain text.
 *
 * @param seq the change sequence; strictly increasing, and the id of the event on the stream
 * @param conversationId the conversation
 * @param timestamp when it happened
 * @param kind STATE, MESSAGE, TOOL, PLAN, REQUEST, TERMINAL, RESULT or NOTICE
 * @param itemId the item it belongs to
 * @param data the details
 */
@Schema(description = "One thing that happened in a conversation")
public record ConversationEventResponse(
    @Schema(description = "The change sequence; the id of the event on the stream") long seq,
    @Schema(description = "The conversation") String conversationId,
    @Schema(description = "When it happened") Instant timestamp,
    @Schema(
            description = "What it describes",
            allowableValues = {
              "STATE",
              "MESSAGE",
              "TOOL",
              "PLAN",
              "REQUEST",
              "TERMINAL",
              "RESULT",
              "NOTICE"
            })
        String kind,
    @Schema(description = "The item it belongs to", nullable = true) @Nullable String itemId,
    @Schema(description = "The details; the keys depend on the kind") Map<String, Object> data) {

  /** Keeps immutable copies of the collections. */
  public ConversationEventResponse {
    data = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
  }
}
