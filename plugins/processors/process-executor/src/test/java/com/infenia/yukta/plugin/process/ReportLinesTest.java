// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.PluginLogSink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ReportLines. */
@NoArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
class ReportLinesTest {

  private static Map<String, Object> failure(
      final String kind,
      final String file,
      final Integer line,
      final String rule,
      final String test) {
    final Map<String, Object> failure = new HashMap<>();
    failure.put("kind", kind);
    failure.put("file", file);
    failure.put("line", line);
    failure.put("rule", rule);
    failure.put("message", "expected 2 | got\n3");
    if (test != null) {
      failure.put("test", test);
    }
    return failure;
  }

  @Test
  void test_becomesTestFailureLine() {
    assertThat(ReportLines.line(failure("test", "com.x.FooTest", 27, null, "adds")))
        .isEqualTo("TestFailure: com.x.FooTest#adds | com.x.FooTest:27 | expected 2 / got 3");
    assertThat(ReportLines.line(failure("test", "com.x.FooTest", null, null, null)))
        .isEqualTo("TestFailure: com.x.FooTest | com.x.FooTest | expected 2 / got 3");
  }

  @Test
  void styleAndAnalysis_becomeLintFailureLines() {
    assertThat(ReportLines.line(failure("style", "Foo.java", 3, "MagicNumber", null)))
        .isEqualTo("LintFailure: MagicNumber | Foo.java:3 | expected 2 / got 3");
    assertThat(ReportLines.line(failure("analysis", "", 3, null, null)))
        .isEqualTo("LintFailure:  |  | expected 2 / got 3");
  }

  @Test
  void write_logsEveryFailureOfPayloadAndIgnoresOthers() {
    final List<String> lines = new ArrayList<>();
    final PluginLogSink sink = (stream, line, level) -> lines.add(stream + ":" + line);

    ReportLines.write(
        Map.of("failures", List.of(failure("style", "A.java", 1, "R", null), "not a map")), sink);
    ReportLines.write(Map.of("failures", "not a list"), sink);
    ReportLines.write("raw stdout", sink);

    assertThat(lines).containsExactly("stdout:LintFailure: R | A.java:1 | expected 2 / got 3");
  }
}
