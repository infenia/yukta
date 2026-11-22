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
 * Failures in {@code go test}, {@code go build} and {@code go vet} output: failed tests ({@code ---
 * FAIL: TestX}) with the first assertion line under them, compile errors and failed packages.
 */
@Component
@Order(20)
public class GoFailureExtractor implements FailureExtractor {

  private static final Pattern TEST = Pattern.compile("^\\s*--- FAIL: (\\S+)");

  /** An assertion line under a failed test: {@code foo_test.go:12: got 1, want 2}. */
  private static final Pattern ASSERTION = Pattern.compile("^\\s+(\\S+\\.go):(\\d+): (.+)$");

  /** A compile or vet error: {@code ./x.go:3:5: undefined: y}. */
  private static final Pattern COMPILE = Pattern.compile("^(\\S+\\.go):(\\d+):\\d+: (.+)$");

  /** A failed package: {@code FAIL github.com/x/y 0.012s}. */
  private static final Pattern PACKAGE = Pattern.compile("^FAIL\\s+(\\S+)\\s+(?:\\d|\\[)");

  /** Lines under a failed test searched for its assertion. */
  private static final int TEST_WINDOW = 6;

  @Override
  public List<Finding> extract(final NodeLog log) {
    final List<Finding> found = new ArrayList<>();
    final List<String> lines = log.lines();
    for (int i = 0; i < lines.size(); i++) {
      final String line = lines.get(i);
      Matcher matcher = TEST.matcher(line);
      if (matcher.find()) {
        final Matcher assertion = log.ahead(i, TEST_WINDOW, ASSERTION);
        found.add(
            log.finding(
                Finding.Kind.TEST,
                i,
                matcher.group(1),
                assertion == null ? null : assertion.group(1) + ":" + assertion.group(2),
                assertion == null ? "FAIL" : assertion.group(3)));
        continue;
      }
      matcher = COMPILE.matcher(line);
      if (matcher.find()) {
        found.add(
            log.finding(
                Finding.Kind.COMPILE,
                i,
                null,
                matcher.group(1) + ":" + matcher.group(2),
                matcher.group(3)));
        continue;
      }
      matcher = PACKAGE.matcher(line);
      if (matcher.find()) {
        found.add(log.finding(Finding.Kind.TASK, i, matcher.group(1), null, "FAIL"));
      }
    }
    return found;
  }
}
