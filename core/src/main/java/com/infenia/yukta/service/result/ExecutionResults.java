// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.result;

import java.util.List;

/**
 * What an execution's terminal nodes have received so far.
 *
 * @param executionId the execution
 * @param complete true once the execution ended, so no more events will come
 * @param status the execution's final status, or null while it runs
 * @param truncated true if the replay buffer dropped earlier events
 * @param nextCursor pass as {@code afterCursor} on the next read to get only newer events
 * @param events the events, oldest first
 */
public record ExecutionResults(
    String executionId,
    boolean complete,
    String status,
    boolean truncated,
    long nextCursor,
    List<ResultEvent> events) {

  /** Compact constructor to ensure immutability. */
  public ExecutionResults {
    events = events == null ? List.of() : List.copyOf(events);
  }
}
