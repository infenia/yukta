// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The failed tests a plugin read from a structured report and wrote as one line each, {@code
 * TestFailure: <test> | <file:line> | <message>} (see {@link #PREFIX}). These are exact, so they
 * are read first.
 */
@Component
@Order(0)
public class ReportLineExtractor implements FailureExtractor {

  /** What a report line starts with. */
  public static final String PREFIX = "TestFailure:";

  private static final Pattern LINE = Pattern.compile("^" + PREFIX + " (.+?) \\| (.*?) \\| (.*)$");

  @Override
  public List<Finding> extract(final NodeLog log) {
    final List<Finding> found = new ArrayList<>();
    for (int i = 0; i < log.lines().size(); i++) {
      final Matcher matcher = LINE.matcher(log.lines().get(i));
      if (matcher.find()) {
        found.add(
            log.finding(
                Finding.Kind.TEST,
                i,
                matcher.group(1),
                matcher.group(2).isBlank() ? null : matcher.group(2),
                matcher.group(3)));
      }
    }
    return found;
  }
}
