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
 * Failures in JavaScript and TypeScript tool output: Vitest and Jest failed tests, {@code tsc}
 * errors and ESLint errors (stylish format, a file line followed by its problems).
 */
@Component
@Order(30)
public class JsFailureExtractor implements FailureExtractor {

  /** Vitest {@code FAIL src/a.test.ts > suite > test}, Jest {@code FAIL src/a.test.ts}. */
  private static final Pattern FAIL =
      Pattern.compile("^\\s*FAIL\\s+(\\S+\\.[cm]?[jt]sx?)(?:\\s+>\\s+(.+?))?\\s*$");

  /** A failed test in the run list: {@code × adds numbers 3ms}. */
  private static final Pattern CROSS = Pattern.compile("^\\s*[×✕✗]\\s+(.+?)(?:\\s+\\d+\\s?ms)?$");

  /** The reason under a failed test. */
  private static final Pattern REASON =
      Pattern.compile(
          "((?:Assertion|Type|Reference|Range|Syntax)?Error: .+|(?<![A-Za-z])expected .+)$");

  /** {@code src/a.ts(3,5): error TS2322: Type 'x' is not assignable}. */
  private static final Pattern TSC =
      Pattern.compile("^(\\S+\\.[cm]?tsx?)\\((\\d+),\\d+\\): error (TS\\d+): (.+)$");

  /** {@code src/a.ts:3:5 - error TS2322: ...} (tsc --pretty). */
  private static final Pattern TSC_PRETTY =
      Pattern.compile("^(\\S+\\.[cm]?tsx?):(\\d+):\\d+ - error (TS\\d+): (.+)$");

  /** An ESLint file header: an absolute path alone on its line. */
  private static final Pattern ESLINT_FILE = Pattern.compile("^(/\\S+\\.[cm]?[jt]sx?)$");

  /** {@code 3:5 error 'x' is defined but never used no-unused-vars}. */
  private static final Pattern ESLINT =
      Pattern.compile("^\\s+(\\d+):\\d+\\s+error\\s+(.+?)\\s{2,}(\\S+)$");

  /** Lines under a failed test searched for its reason. */
  private static final int TEST_WINDOW = 8;

  @Override
  public List<Finding> extract(final NodeLog log) {
    final List<Finding> found = new ArrayList<>();
    String eslintFile = null;
    final List<String> lines = log.lines();
    for (int i = 0; i < lines.size(); i++) {
      final String line = lines.get(i);
      final Matcher file = ESLINT_FILE.matcher(line);
      if (file.find()) {
        eslintFile = file.group(1);
        continue;
      }
      final Finding finding = finding(log, i, line, eslintFile);
      if (finding != null) {
        found.add(finding);
      }
    }
    return found;
  }

  private static @Nullable Finding finding(
      final NodeLog log, final int index, final String line, @Nullable final String eslintFile) {
    Matcher matcher = FAIL.matcher(line);
    if (matcher.find()) {
      final String name = matcher.group(2) == null ? matcher.group(1) : matcher.group(2);
      return log.finding(Finding.Kind.TEST, index, name, matcher.group(1), reasonAfter(log, index));
    }
    matcher = CROSS.matcher(line);
    if (matcher.find()) {
      return log.finding(Finding.Kind.TEST, index, matcher.group(1), null, reasonAfter(log, index));
    }
    matcher = TSC.matcher(line);
    if (!matcher.find()) {
      matcher = TSC_PRETTY.matcher(line);
    }
    if (matcher.find(0)) {
      return log.finding(
          Finding.Kind.COMPILE,
          index,
          matcher.group(3),
          matcher.group(1) + ":" + matcher.group(2),
          matcher.group(4));
    }
    matcher = ESLINT.matcher(line);
    if (eslintFile != null && matcher.find()) {
      return log.finding(
          Finding.Kind.LINT,
          index,
          matcher.group(3),
          eslintFile + ":" + matcher.group(1),
          matcher.group(2));
    }
    return null;
  }

  private static String reasonAfter(final NodeLog log, final int index) {
    final Matcher reason = log.ahead(index, TEST_WINDOW, REASON);
    return reason == null ? "FAIL" : reason.group(1);
  }
}
