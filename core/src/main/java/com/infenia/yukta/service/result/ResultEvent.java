// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.result;

import java.time.Instant;

/**
 * One event on an execution's result stream.
 *
 * @param kind what the event reports
 * @param nodeId the terminal node, or null for the end of the execution
 * @param sequence position in the execution's stream, counting from 0
 * @param payload the message payload for {@link Kind#RESULT}, otherwise null
 * @param status the node or execution status for the end events, otherwise null
 * @param truncated for the end events: true if earlier events were dropped from the replay buffer
 *     (live subscribers still received them)
 * @param timestamp when the event was produced
 */
public record ResultEvent(
    Kind kind,
    String nodeId,
    long sequence,
    Object payload,
    String status,
    boolean truncated,
    Instant timestamp) {

  /** What an event reports. */
  public enum Kind {
    /** A message reached a terminal node. */
    RESULT,
    /** A terminal node finished. */
    NODE_END,
    /** The execution finished; the stream ends after this event. */
    EXECUTION_END
  }
}
