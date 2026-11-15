// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One thing that happened in a conversation. Events with the same {@code itemId} describe one item:
 * {@code MESSAGE} pieces are appended in order, a later {@code TOOL}, {@code PLAN} or {@code
 * REQUEST} event replaces the fields it carries.
 *
 * @param seq the change sequence; assigned by the store, strictly increasing
 * @param conversationId the conversation
 * @param timestamp when it happened
 * @param kind what it describes; the keys of {@code data} depend on it
 * @param itemId the item it belongs to, or null
 * @param data the details; never null, may be empty
 * @since 0.1.0-alpha.4
 */
public record AgentEvent(
    long seq,
    String conversationId,
    Instant timestamp,
    AgentEventKind kind,
    @Nullable String itemId,
    Map<String, Object> data) {

  /**
   * Checks the parts and keeps an unmodifiable copy of the data, in its order.
   *
   * @param seq the change sequence
   * @param conversationId the conversation
   * @param timestamp when it happened
   * @param kind what it describes
   * @param itemId the item, or null
   * @param data the details
   */
  public AgentEvent {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(kind, "kind");
    data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
  }
}
