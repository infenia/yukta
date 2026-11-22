// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.plugin.core.PluginLogSink;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.experimental.UtilityClass;

/**
 * Writes each failure read from a structured report as one log line, so a reader of the run log
 * (the failure digest, a grep) finds it without the output message: {@code TestFailure:
 * <class#test> | <file:line> | <message>} for a test, {@code LintFailure: <rule> | <file:line> |
 * <message>} for a style or analysis finding. The server's {@code ReportLineExtractor} reads
 * exactly this format.
 */
@UtilityClass
/* package */ class ReportLines {

  /** What a failed test's line starts with. */
  /* package */ static final String TEST_PREFIX = "TestFailure: ";

  /** The kind {@link ReportFailures} gives a failed test. */
  private static final String TEST_KIND = "test";

  /** Between the fields of a line. */
  private static final String SEPARATOR = " | ";

  /** What a style or analysis finding's line starts with. */
  /* package */ static final String LINT_PREFIX = "LintFailure: ";

  /**
   * Writes the failures of a payload, if it holds any.
   *
   * @param payload the node's output payload
   * @param sink where the node's log lines go
   */
  /* package */ static void write(final Object payload, final PluginLogSink sink) {
    if (payload instanceof final Map<?, ?> map
        && map.get("failures") instanceof final List<?> list) {
      for (final Object failure : list) {
        if (failure instanceof final Map<?, ?> fields) {
          sink.line("stdout", line(fields));
        }
      }
    }
  }

  /**
   * The log line of one failure.
   *
   * @param failure a failure as {@link ReportFailures} reads it
   * @return the line
   */
  /* package */ static String line(final Map<?, ?> failure) {
    final String file = text(failure.get("file"));
    final Object number = failure.get("line");
    final String location = file.isEmpty() || number == null ? file : file + ":" + number;
    final String message = text(failure.get("message")).replace('|', '/');
    final boolean test = TEST_KIND.equals(failure.get("kind"));
    final String name;
    if (test) {
      final String method = text(failure.get("test"));
      name = method.isEmpty() ? file : file + "#" + method;
    } else {
      name = text(failure.get("rule"));
    }
    return String.join(SEPARATOR, (test ? TEST_PREFIX : LINT_PREFIX) + name, location, message);
  }

  private static String text(final Object value) {
    return Objects.toString(value, "").replace('\n', ' ').strip();
  }
}
