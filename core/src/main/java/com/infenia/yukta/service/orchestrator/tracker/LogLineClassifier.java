// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.plugin.core.LogLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.NoArgsConstructor;

/**
 * Rates the log lines of one node. Process output carries no level, so this is the one place that
 * reads keywords; everything downstream (events API, UI, supervisors, CLI) gets the result as data.
 *
 * <p>It remembers that a line announced gradle's "What went wrong", because the lines after it are
 * the cause even when they have no keyword. An instance belongs to one node and is not thread-safe;
 * the tracker applies events on a single thread.
 */
@NoArgsConstructor
public final class LogLineClassifier {

  /** Cap on the problem lines reported for one failure reason. */
  public static final int MAX_PROBLEMS = 20;

  /** Cap on the length of one problem line. */
  private static final int MAX_PROBLEM_CHARS = 300;

  /** Lines after gradle's "What went wrong" that are its cause. */
  private static final int WENT_WRONG_CONTEXT = 3;

  /** Words that make a line an error, and gradle's pointer to its report. */
  private static final Pattern ERROR_LINE =
      Pattern.compile(
          "\\b(error|failed|failure)\\b|exception|What went wrong|See the report at",
          Pattern.CASE_INSENSITIVE);

  /** Words that make a line a warning. */
  private static final Pattern WARN_LINE =
      Pattern.compile("\\bwarn(ing)?\\b|deprecat", Pattern.CASE_INSENSITIVE);

  /** Cause lines still to rate as errors after "What went wrong". */
  private int context;

  /**
   * Rates the next line of the node.
   *
   * @param line the line
   * @return its level: {@code ERROR}, {@code WARN} or {@code INFO}
   */
  public LogLevel rate(final String line) {
    final boolean wentWrong = line.contains("What went wrong");
    // the next gradle section ("* Try:") ends the cause
    final boolean inCause = !wentWrong && context > 0 && !line.stripLeading().startsWith("* ");
    if (wentWrong) {
      context = WENT_WRONG_CONTEXT;
    } else if (inCause) {
      context--;
    } else {
      context = 0;
    }
    final LogLevel level;
    if (wentWrong || inCause || ERROR_LINE.matcher(line).find()) {
      level = LogLevel.ERROR;
    } else {
      level = WARN_LINE.matcher(line).find() ? LogLevel.WARN : LogLevel.INFO;
    }
    return level;
  }

  /**
   * The warning and error lines of a failure reason, for the failed node's status event. The reason
   * is one text, so it gets a fresh classifier of its own.
   *
   * @param text the reason, possibly several lines
   * @return {@code level} and {@code line} for each problem line, at most {@value #MAX_PROBLEMS}
   */
  public static List<Map<String, String>> problems(final String text) {
    final LogLineClassifier classifier = new LogLineClassifier();
    final List<Map<String, String>> problems = new ArrayList<>();
    for (final String raw : text.split("\n")) {
      final String line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      final LogLevel level = classifier.rate(line);
      if (level != LogLevel.INFO) {
        final String shown =
            line.length() > MAX_PROBLEM_CHARS ? line.substring(0, MAX_PROBLEM_CHARS) + "…" : line;
        problems.add(Map.of("level", level.name(), "line", shown));
        if (problems.size() == MAX_PROBLEMS) {
          break;
        }
      }
    }
    return problems;
  }
}
