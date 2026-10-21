// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.LogLevel;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/** Tests for {@link LogLineClassifier}. */
@NoArgsConstructor
class LogLineClassifierTest {

  @ParameterizedTest
  @CsvFileSource(resources = "line-severity.csv", numLinesToSkip = 1)
  void rate_matchesTheKeywords(final String line, final LogLevel expected) {
    assertThat(new LogLineClassifier().rate(line)).isEqualTo(expected);
  }

  @Test
  void rate_causeLinesAfterWhatWentWrongAreErrorsUntilTheNextSection() {
    final LogLineClassifier classifier = new LogLineClassifier();

    assertThat(classifier.rate("* What went wrong:")).isEqualTo(LogLevel.ERROR);
    assertThat(classifier.rate("Selection broke")).isEqualTo(LogLevel.ERROR);
    assertThat(classifier.rate("* Try:")).isEqualTo(LogLevel.INFO);
    assertThat(classifier.rate("> Run with --stacktrace")).isEqualTo(LogLevel.INFO);
  }

  @Test
  void rate_causeContextEndsAfterThreeLines() {
    final LogLineClassifier classifier = new LogLineClassifier();
    classifier.rate("What went wrong");

    assertThat(List.of("one", "two", "three").stream().map(classifier::rate))
        .containsOnly(LogLevel.ERROR);
    assertThat(classifier.rate("four")).isEqualTo(LogLevel.INFO);
  }

  @Test
  void problems_keepsOnlyRatedLines() {
    final List<Map<String, String>> problems =
        LogLineClassifier.problems(
            "Process failed with exit code 1\n--- Output ---\n\n> Task :x\nwarning: careful\n");

    assertThat(problems)
        .containsExactly(
            Map.of("level", "ERROR", "line", "Process failed with exit code 1"),
            Map.of("level", "WARN", "line", "warning: careful"));
  }

  @Test
  void problems_capsTheCountAndTheLineLength() {
    final String many = "error number\n".repeat(LogLineClassifier.MAX_PROBLEMS + 5);

    assertThat(LogLineClassifier.problems(many)).hasSize(LogLineClassifier.MAX_PROBLEMS);
    assertThat(LogLineClassifier.problems("error: " + "x".repeat(1000)))
        .singleElement()
        .satisfies(p -> assertThat(p.get("line")).hasSize(301).endsWith("…"));
  }

  @Test
  void problems_isEmptyWithoutKeywords() {
    assertThat(LogLineClassifier.problems("Process timed out after 3600s")).isEmpty();
  }
}
