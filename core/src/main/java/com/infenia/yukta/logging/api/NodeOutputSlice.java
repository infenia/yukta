// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.util.List;

/**
 * A slice of the output a node wrote.
 *
 * @param nodeId the node
 * @param stream the stream read: {@code stdout}, {@code stderr} or {@code all}
 * @param totalLines how many lines the node wrote in that stream
 * @param totalBytes how many bytes those lines hold
 * @param matchedLines how many lines were selected before the window was cut (all of them without
 *     {@code grep}, the matching ones and their context with it)
 * @param truncated whether selected lines were left out of {@code lines}
 * @param nextOffset the {@code offset} of the next page, or {@code null} on the last page and for
 *     {@code tail} and {@code head} reads
 * @param lines the lines, numbered from 1 in the stream
 */
public record NodeOutputSlice(
    String nodeId,
    String stream,
    long totalLines,
    long totalBytes,
    long matchedLines,
    boolean truncated,
    Long nextOffset,
    List<Line> lines) {

  /** Keeps the lines as they were given. */
  public NodeOutputSlice {
    lines = List.copyOf(lines);
  }

  /**
   * One line.
   *
   * @param number the line number in the stream, from 1
   * @param text the text, cut at 2000 characters
   */
  public record Line(long number, String text) {}
}
