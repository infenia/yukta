/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.plugin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Data envelope for workflow execution.
 *
 * @param id unique identifier for the message
 * @param traceId identifier for tracing the workflow execution
 * @param metadata metadata associated with the message
 * @param payload the actual data being passed
 * @param timestamp time when the message was created
 * @param sourcePort the port from which the message was emitted
 * @param sourceNodeId the ID of the node that emitted the message
 */
public record Message(
    @SuppressWarnings("PMD.ShortVariable") UUID id,
    UUID traceId,
    Map<String, Object> metadata,
    Object payload,
    Instant timestamp,
    String sourcePort,
    String sourceNodeId) {

  /**
   * Compact constructor to ensure metadata is immutable.
   *
   * @param id unique identifier
   * @param traceId trace identifier
   * @param metadata metadata map
   * @param payload payload object
   * @param timestamp creation timestamp
   * @param sourcePort emission port
   * @param sourceNodeId source node ID
   */
  public Message {
    metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
  }

  /**
   * Create a new message with default values.
   *
   * @param traceId the trace identifier
   * @param payload the payload
   * @return a new message
   */
  public static Message create(final UUID traceId, final Object payload) {
    return new Message(UUID.randomUUID(), traceId, Map.of(), payload, Instant.now(), null, null);
  }

  /**
   * Create a copy of this message with a new source port.
   *
   * @param newSourcePort the new source port
   * @return a new message instance
   */
  public Message withSourcePort(final String newSourcePort) {
    return new Message(id, traceId, metadata, payload, timestamp, newSourcePort, sourceNodeId);
  }

  /**
   * Create a copy of this message with a new source node ID.
   *
   * @param newSourceNodeId the new source node ID
   * @return a new message instance
   */
  public Message withSourceNodeId(final String newSourceNodeId) {
    return new Message(id, traceId, metadata, payload, timestamp, sourcePort, newSourceNodeId);
  }
}
