// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The answer to a {@link RunLogQuery}: counts per node first, lines only in {@link
 * LogSearchMode#LINES} mode.
 *
 * @param executionId the run searched
 * @param mode the mode answered
 * @param executionsScanned how many runs were searched (more than one with children)
 * @param linesScanned how many log lines those runs hold
 * @param matches how many lines matched in all of them
 * @param nodes per node of every run, its counts: in COUNT mode every node, otherwise the nodes
 *     with a match; the ones with most matches and errors first, at most {@value #MAX_NODES}
 * @param nodesOmitted how many nodes were left out of {@code nodes}
 * @param lines the lines returned, run by run in the order they were written (LINES mode)
 * @param truncated whether matching lines were left out because of a budget
 * @param nextCursor the {@code cursor} for the next page, or {@code null} on the last one
 * @param hint the cheapest useful next search, in words, or {@code null}
 */
public record RunLogSearch(
    String executionId,
    LogSearchMode mode,
    long executionsScanned,
    long linesScanned,
    long matches,
    List<NodeCount> nodes,
    int nodesOmitted,
    List<Line> lines,
    boolean truncated,
    @Schema(nullable = true) @Nullable String nextCursor,
    @Schema(nullable = true) @Nullable String hint) {

  /** Most nodes listed in one answer. */
  public static final int MAX_NODES = 50;

  /** Keeps the lists as they were given. */
  public RunLogSearch {
    nodes = List.copyOf(nodes);
    lines = List.copyOf(lines);
  }

  /**
   * The counts of one node of one run.
   *
   * @param executionId the run
   * @param nodeId the node
   * @param lines lines it wrote in the searched stream
   * @param errors those rated ERROR
   * @param warnings those rated WARN
   * @param matches those that matched the search
   */
  public record NodeCount(
      String executionId, String nodeId, long lines, long errors, long warnings, long matches) {}

  /**
   * One line.
   *
   * @param executionId the run that wrote it
   * @param nodeId the node that wrote it
   * @param sequence its position in the run's log, from 0
   * @param stream {@code stdout}, {@code stderr} or {@code custom}
   * @param level its level
   * @param match whether it matched, rather than being context
   * @param text the text, cut at 2000 characters
   */
  public record Line(
      String executionId,
      String nodeId,
      long sequence,
      String stream,
      String level,
      boolean match,
      String text) {}
}
