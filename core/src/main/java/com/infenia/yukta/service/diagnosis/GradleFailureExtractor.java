// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Failures in Gradle console output: failed JUnit tests ({@code Class > method() FAILED}), javac
 * and Kotlin compile errors, Checkstyle and PMD violations, failed tasks and the cause under "What
 * went wrong".
 */
@Component
@Order(10)
public class GradleFailureExtractor implements FailureExtractor {

  private static final Pattern TEST = Pattern.compile("^(\\S+) > (.+?) FAILED\\s*$");

  /** The first indented line after a failed test: the assertion or exception. */
  private static final Pattern TEST_REASON = Pattern.compile("^\\s+(\\S.*)$");

  /** A stack frame in a source file ({@code at app//com.x.FooTest.adds(FooTest.java:27)}). */
  private static final String FRAME =
      "^\\s+at [\\w.$<>/@-]+\\((%s\\.(?:java|kt|groovy|scala)):(\\d+)\\)";

  /** A frame in any source file, when none is in the test's own file. */
  private static final Pattern ANY_FRAME = Pattern.compile(FRAME.formatted("\\w+"));

  private static final Pattern JAVAC =
      Pattern.compile("^(\\S.*?\\.(?:java|groovy|scala)):(\\d+): error: (.+)$");

  private static final Pattern KOTLINC =
      Pattern.compile("^e: (?:file://)?(\\S.*?\\.kts?):(\\d+):\\d+ (.+)$");

  private static final Pattern CHECKSTYLE =
      Pattern.compile(
          "^\\[ant:checkstyle\\] \\[(?:ERROR|WARN)\\] (\\S.*?):(\\d+)(?::\\d+)?: (.+?)"
              + "(?: \\[(\\w+)\\])?$");

  /**
   * A PMD violation; its rule is CamelCase, so a javac {@code warning:} or {@code note:} is not
   * one.
   */
  private static final Pattern PMD =
      Pattern.compile("^(\\S.*?\\.java):(\\d+):\\s+(\\p{Lu}\\w+):\\s+(.+)$");

  private static final Pattern TASK = Pattern.compile("^> Task (:\\S+) FAILED\\s*$");

  private static final Pattern WENT_WRONG = Pattern.compile("^\\* What went wrong:");

  private static final Pattern FAILED_TASK =
      Pattern.compile("^Execution failed for task '(:\\S+)'");

  /** Lines of the cause read after "What went wrong". */
  private static final int CAUSE_LINES = 3;

  /** Lines after a failed test searched for its reason and its source line. */
  private static final int TEST_WINDOW = 12;

  @Override
  public List<Finding> extract(final NodeLog log) {
    final List<Finding> found = new ArrayList<>();
    final List<String> lines = log.lines();
    for (int i = 0; i < lines.size(); i++) {
      final String line = lines.get(i);
      final Finding finding = failedTest(log, i, line);
      if (finding != null) {
        found.add(finding);
      } else if (WENT_WRONG.matcher(line).find()) {
        found.add(wentWrong(log, i));
      } else {
        final Finding other = other(log, i, line);
        if (other != null) {
          found.add(other);
        }
      }
    }
    return found;
  }

  private static @Nullable Finding failedTest(
      final NodeLog log, final int index, final String line) {
    final Matcher test = TEST.matcher(line);
    if (!test.find()) {
      return null;
    }
    final Matcher reason = log.ahead(index, 2, TEST_REASON);
    // the frame in the test's own file says where it failed; the first frame is often JUnit's
    final String className = test.group(1);
    final String simpleName = className.substring(className.lastIndexOf('.') + 1).split("\\$")[0];
    Matcher frame =
        log.ahead(index, TEST_WINDOW, Pattern.compile(FRAME.formatted(Pattern.quote(simpleName))));
    if (frame == null) {
      frame = log.ahead(index, TEST_WINDOW, ANY_FRAME);
    }
    return log.finding(
        Finding.Kind.TEST,
        index,
        test.group(1) + " > " + test.group(2),
        frame == null ? null : frame.group(1) + ":" + frame.group(2),
        reason == null ? "FAILED" : reason.group(1));
  }

  private static @Nullable Finding other(final NodeLog log, final int index, final String line) {
    Matcher matcher = JAVAC.matcher(line);
    if (matcher.find()) {
      return log.finding(
          Finding.Kind.COMPILE,
          index,
          null,
          matcher.group(1) + ":" + matcher.group(2),
          matcher.group(3));
    }
    matcher = KOTLINC.matcher(line);
    if (matcher.find()) {
      return log.finding(
          Finding.Kind.COMPILE,
          index,
          null,
          matcher.group(1) + ":" + matcher.group(2),
          matcher.group(3));
    }
    matcher = CHECKSTYLE.matcher(line);
    if (matcher.find()) {
      return log.finding(
          Finding.Kind.LINT,
          index,
          matcher.group(4),
          matcher.group(1) + ":" + matcher.group(2),
          matcher.group(3));
    }
    matcher = PMD.matcher(line);
    if (matcher.find()) {
      return log.finding(
          Finding.Kind.LINT,
          index,
          matcher.group(3),
          matcher.group(1) + ":" + matcher.group(2),
          matcher.group(4));
    }
    matcher = TASK.matcher(line);
    if (matcher.find()) {
      return log.finding(Finding.Kind.TASK, index, matcher.group(1), null, "FAILED");
    }
    return null;
  }

  /** The cause Gradle prints under "What went wrong", up to the next section. */
  private static Finding wentWrong(final NodeLog log, final int index) {
    final List<String> cause = cause(log.lines(), index);
    final String first = cause.isEmpty() ? "" : cause.getFirst();
    final Matcher task = FAILED_TASK.matcher(first);
    return task.find()
        ? log.finding(
            Finding.Kind.TASK,
            index,
            task.group(1),
            null,
            String.join(" ", cause.subList(1, cause.size())))
        : log.finding(Finding.Kind.ERROR, index, null, null, String.join(" ", cause));
  }

  private static List<String> cause(final List<String> lines, final int index) {
    final List<String> cause = new ArrayList<>();
    for (int i = index + 1; i < lines.size() && cause.size() < CAUSE_LINES; i++) {
      final String text = lines.get(i).strip();
      if (text.startsWith("* ") || (text.isEmpty() && !cause.isEmpty())) {
        break;
      }
      if (!text.isEmpty()) {
        cause.add(text.startsWith("> ") ? text.substring(2) : text);
      }
    }
    return cause;
  }
}
