// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.NodeOutputPager;
import com.infenia.yukta.plugin.core.LogLevel;
import java.util.Set;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A search over the logs of one run, and optionally of every run it started.
 *
 * <p>A line matches when it passes every filter given: its node is in {@code nodes}, it was written
 * to {@code stream}, its level is at least {@code level} and it contains {@code grep}. Without
 * filters every line matches, so {@link LogSearchMode#COUNT} gives the lines per node.
 *
 * @param executionId the run
 * @param includeChildren whether the runs it started (at any depth) are searched too
 * @param nodes the nodes to search; empty for all
 * @param stream the stream to search, or {@code null} for all
 * @param level the lowest level a line may have, or {@code null} for any
 * @param grep a regular expression a line must contain, or {@code null}
 * @param context lines of the same node either side of a match (LINES mode), at most {@value
 *     NodeOutputPager#MAX_CONTEXT}
 * @param mode what to return; {@code null} for {@link LogSearchMode#COUNT}
 * @param maxMatches the most matching lines returned, 0 for {@value #DEFAULT_MATCHES}; at most
 *     {@value #MAX_MATCHES}
 * @param maxBytes the most bytes of line text returned, 0 for {@value #DEFAULT_BYTES}; at most
 *     {@value #MAX_BYTES}
 * @param cursor the {@code nextCursor} of the previous page, or {@code null} for the first
 */
@Builder
@SuppressWarnings({"PMD.DataClass", "PMD.NullAssignment"})
public record RunLogQuery(
    String executionId,
    boolean includeChildren,
    Set<String> nodes,
    @Nullable LogStream stream,
    @Nullable LogLevel level,
    @Nullable String grep,
    int context,
    LogSearchMode mode,
    int maxMatches,
    int maxBytes,
    @Nullable String cursor) {

  /** Matching lines returned when no budget is given. */
  public static final int DEFAULT_MATCHES = 50;

  /** Most matching lines one answer may hold. */
  public static final int MAX_MATCHES = 500;

  /** Bytes of line text returned when no budget is given. */
  public static final int DEFAULT_BYTES = 16 * 1024;

  /** Most bytes of line text one answer may hold. */
  public static final int MAX_BYTES = 64 * 1024;

  /**
   * Fills in the defaults and caps the budgets.
   *
   * @throws IllegalArgumentException for a blank execution or a negative number
   */
  public RunLogQuery {
    if (executionId == null || executionId.isBlank()) {
      throw new IllegalArgumentException("executionId is required");
    }
    if (context < 0 || maxMatches < 0 || maxBytes < 0) {
      throw new IllegalArgumentException("context, maxMatches and maxBytes must not be negative");
    }
    nodes = nodes == null ? Set.of() : Set.copyOf(nodes);
    mode = mode == null ? LogSearchMode.COUNT : mode;
    grep = grep == null || grep.isEmpty() ? null : grep;
    context = Math.min(context, NodeOutputPager.MAX_CONTEXT);
    maxMatches = maxMatches == 0 ? DEFAULT_MATCHES : Math.min(maxMatches, MAX_MATCHES);
    maxBytes = maxBytes == 0 ? DEFAULT_BYTES : Math.min(maxBytes, MAX_BYTES);
  }
}
