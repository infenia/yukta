// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import java.time.Instant;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link EventSeverity}. */
@NoArgsConstructor
class EventSeverityTest {

  /** Timestamp of every event built here. */
  private static final Instant NOW = Instant.now();

  @ParameterizedTest
  @CsvSource({
    "RUNNING,INFO",
    "SUCCESS,INFO",
    "UPSTREAM_FAILED,INFO",
    "CANCELLED,WARN",
    "FAILURE,ERROR",
    "TIMED_OUT,ERROR"
  })
  void nodeStatus(final String status, final EventSeverity expected) {
    final var event =
        new ExecutionEvent.NodeStatusChanged("e", "w", "n", "m", status, Map.of(), NOW);

    assertThat(EventSeverity.of(event)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"RUNNING,INFO", "COMPLETED,INFO", "CANCELLED,WARN", "FAILURE,ERROR", "ABORTED,ERROR"})
  void workflowStatus(final String status, final EventSeverity expected) {
    final var event = new ExecutionEvent.WorkflowStatusChanged("e", "w", status, NOW);

    assertThat(EventSeverity.of(event)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"true,INFO", "false,WARN"})
  void controlOutcome(final boolean applied, final EventSeverity expected) {
    final var event = new ExecutionEvent.ControlOutcome("e", "Pause", null, applied, null, NOW);

    assertThat(EventSeverity.of(event)).isEqualTo(expected);
  }

  /**
   * The CLI keeps the same keywords for text the server rates only as a whole; its test reads this
   * file too, so the two cannot drift apart unnoticed.
   */
  @ParameterizedTest
  @CsvFileSource(
      files = "../cli/internal/commands/controlbus/testdata/line-severity.csv",
      numLinesToSkip = 1)
  void logLine(final String line, final EventSeverity expected) {
    final var event = new ExecutionEvent.LogLine("e", "n", line, NOW);

    assertThat(EventSeverity.of(event)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"warn,WARN", " ERROR ,ERROR", "Info,INFO"})
  void parse_acceptsAnyCase(final String name, final EventSeverity expected) {
    assertThat(EventSeverity.parse(name)).isEqualTo(expected);
  }
}
