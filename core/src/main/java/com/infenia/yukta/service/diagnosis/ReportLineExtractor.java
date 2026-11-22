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
 * The failures a plugin read from a structured report (JUnit, Checkstyle, SARIF) and wrote as one
 * line each: {@code TestFailure: <test> | <file:line> | <message>} for a test (see {@link
 * #PREFIX}), {@code LintFailure: <rule> | <file:line> | <message>} for a style or analysis finding
 * ({@code PROCESS_EXECUTOR} with {@code reports} writes them). These are exact, so they are read
 * first.
 */
@Component
@Order(0)
public class ReportLineExtractor implements FailureExtractor {

  /** What a failed test's line starts with. */
  public static final String PREFIX = "TestFailure:";

  /** What a style or analysis finding's line starts with. */
  public static final String LINT_PREFIX = "LintFailure:";

  private static final Pattern LINE =
      Pattern.compile("^(" + PREFIX + "|" + LINT_PREFIX + ") (.*?) \\| (.*?) \\| (.*)$");

  @Override
  public List<Finding> extract(final NodeLog log) {
    final List<Finding> found = new ArrayList<>();
    for (int i = 0; i < log.lines().size(); i++) {
      final Matcher matcher = LINE.matcher(log.lines().get(i));
      if (matcher.find()) {
        found.add(
            log.finding(
                PREFIX.equals(matcher.group(1)) ? Finding.Kind.TEST : Finding.Kind.LINT,
                i,
                blankToNull(matcher.group(2)),
                blankToNull(matcher.group(3)),
                matcher.group(4)));
      }
    }
    return found;
  }

  private static @Nullable String blankToNull(final String text) {
    return text.isBlank() ? null : text.strip();
  }
}
