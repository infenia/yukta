// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.result;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

  /** Payload fields that hold a process's output and are left out unless asked for. */
  private static final Set<String> OUTPUT_FIELDS = Set.of("stdout", "stderr");

  /** Deepest payload nesting searched for output fields. */
  private static final int MAX_DEPTH = 8;

  /** Compact constructor to ensure immutability. */
  public ExecutionResults {
    events = events == null ? List.of() : List.copyOf(events);
  }

  /**
   * Leave the {@code stdout} and {@code stderr} fields out of every payload except the ones named.
   *
   * @param include comma-separated fields to keep ({@code stdout}, {@code stderr}); null or blank
   *     keeps neither
   * @return the results with the other output fields removed
   */
  public ExecutionResults withOutput(final String include) {
    final Set<String> keep =
        include == null
            ? Set.of()
            : Arrays.stream(include.toLowerCase(Locale.ROOT).split(","))
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    final List<ResultEvent> kept =
        events.stream()
            .map(
                event ->
                    new ResultEvent(
                        event.kind(),
                        event.nodeId(),
                        event.sequence(),
                        strip(event.payload(), keep, 0),
                        event.status(),
                        event.truncated(),
                        event.timestamp()))
            .toList();
    return new ExecutionResults(executionId, complete, status, truncated, nextCursor, kept);
  }

  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap"}) // order is kept
  private static Object strip(final Object value, final Set<String> keep, final int depth) {
    if (depth > MAX_DEPTH) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      final Map<Object, Object> out = new LinkedHashMap<>();
      map.forEach(
          (key, item) -> {
            if (!OUTPUT_FIELDS.contains(key) || keep.contains(key)) {
              out.put(key, strip(item, keep, depth + 1));
            }
          });
      return out;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(item -> strip(item, keep, depth + 1)).toList();
    }
    return value;
  }
}
