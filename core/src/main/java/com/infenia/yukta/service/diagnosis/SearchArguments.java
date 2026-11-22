// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.plugin.core.LogLevel;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Reads the text arguments of a log read the same way for every client (REST, MCP, CLI): a stream
 * name, a level name and a comma-separated list of nodes.
 */
@UtilityClass
public class SearchArguments {

  /**
   * A stream name.
   *
   * @param stream {@code stdout}, {@code stderr}, {@code all} or {@code null} (any case)
   * @return the stream, or {@code null} for all
   * @throws IllegalArgumentException for another name
   */
  public static @Nullable LogStream stream(@Nullable final String stream) {
    final String which = stream == null ? "all" : stream.strip().toLowerCase(Locale.ROOT);
    return switch (which) {
      case "all", "" -> null;
      case "stdout" -> LogStream.STDOUT;
      case "stderr" -> LogStream.STDERR;
      default -> throw new IllegalArgumentException("stream must be stdout, stderr or all");
    };
  }

  /**
   * A level name.
   *
   * @param level {@code ERROR}, {@code WARN}, {@code INFO}, {@code DEBUG}, blank or {@code null}
   *     (any case)
   * @return the level, or {@code null} for any
   * @throws IllegalArgumentException for another name
   */
  public static @Nullable LogLevel level(@Nullable final String level) {
    if (level == null || level.isBlank()) {
      return null;
    }
    try {
      return LogLevel.valueOf(level.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("level must be ERROR, WARN, INFO or DEBUG", e);
    }
  }

  /**
   * Node ids from a comma-separated list.
   *
   * @param nodes the list, or {@code null}
   * @return the ids, blanks dropped; empty for all nodes
   */
  public static Set<String> nodes(@Nullable final String nodes) {
    return nodes == null
        ? Set.of()
        : Arrays.stream(nodes.split(","))
            .map(String::strip)
            .filter(node -> !node.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }
}
