// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The lines one node wrote in one run (both streams, in order), as a {@link FailureExtractor} reads
 * them.
 *
 * @param executionId the run
 * @param nodeId the node
 * @param lines the lines, in order, already redacted
 */
public record NodeLog(String executionId, String nodeId, List<String> lines) {

  /** Keeps the lines as they were given. */
  public NodeLog {
    lines = List.copyOf(lines);
  }

  /**
   * A finding at one line of this node.
   *
   * @param kind what failed
   * @param index the line, from 0
   * @param name the test, task or rule, or {@code null}
   * @param location {@code file:line}, or {@code null}
   * @param message the reason
   * @return the finding
   */
  public Finding finding(
      final Finding.Kind kind,
      final int index,
      @Nullable final String name,
      @Nullable final String location,
      final String message) {
    return new Finding(kind, executionId, nodeId, name, location, message.strip(), index + 1L);
  }

  /**
   * The first of the next lines after {@code index} that matches, looking at most {@code window}
   * lines ahead.
   *
   * @param index the line to look after
   * @param window how many lines to look at
   * @param pattern what to find
   * @return the match, or {@code null}
   */
  public @Nullable Matcher ahead(final int index, final int window, final Pattern pattern) {
    final int end = Math.min(lines.size(), index + 1 + window);
    for (int i = index + 1; i < end; i++) {
      final Matcher matcher = pattern.matcher(lines.get(i));
      if (matcher.find()) {
        return matcher;
      }
    }
    return null;
  }
}
