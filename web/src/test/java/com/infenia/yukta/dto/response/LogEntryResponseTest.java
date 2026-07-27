// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for LogEntryResponse. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@NoArgsConstructor
class LogEntryResponseTest {

  /** Execution identifier constant. */
  private static final String EXECUTION_ID = "exec-091-qp-55";

  /** Plugin identifier constant. */
  private static final String PLUGIN_ID = "vectorize-batch";

  /** Plugin name constant. */
  private static final String PLUGIN_NAME = "vector-engine-v2";

  /** Log message constant. */
  private static final String MESSAGE = "Processing batch 1/45...";

  /** Sample timestamp constant. */
  private static final Instant TIMESTAMP = Instant.parse("2026-07-26T14:22:15Z");

  @Test
  void constructor_validInputs_createsRecord() {
    // Given-When
    final LogEntryResponse entry =
        new LogEntryResponse(
            EXECUTION_ID, PLUGIN_ID, PLUGIN_NAME, "STDOUT", MESSAGE, "INFO", TIMESTAMP);

    // Then
    assertThat(entry.executionId()).isEqualTo(EXECUTION_ID);
    assertThat(entry.pluginId()).isEqualTo(PLUGIN_ID);
    assertThat(entry.pluginName()).isEqualTo(PLUGIN_NAME);
    assertThat(entry.stream()).isEqualTo("STDOUT");
    assertThat(entry.message()).isEqualTo(MESSAGE);
    assertThat(entry.level()).isEqualTo("INFO");
    assertThat(entry.timestamp()).isEqualTo(TIMESTAMP);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given-When-Then
    assertThat(
            new LogEntryResponse(
                EXECUTION_ID, PLUGIN_ID, PLUGIN_NAME, "STDERR", MESSAGE, "WARN", TIMESTAMP))
        .isEqualTo(
            new LogEntryResponse(
                EXECUTION_ID, PLUGIN_ID, PLUGIN_NAME, "STDERR", MESSAGE, "WARN", TIMESTAMP));
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given-When-Then
    assertThat(
            new LogEntryResponse(
                EXECUTION_ID, PLUGIN_ID, PLUGIN_NAME, "STDOUT", MESSAGE, "INFO", TIMESTAMP))
        .isNotEqualTo(
            new LogEntryResponse(
                EXECUTION_ID, PLUGIN_ID, PLUGIN_NAME, "STDOUT", MESSAGE, "ERROR", TIMESTAMP));
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given-When
    final String actual =
        new LogEntryResponse(
                EXECUTION_ID, PLUGIN_ID, PLUGIN_NAME, "STDOUT", MESSAGE, "INFO", TIMESTAMP)
            .toString();

    // Then
    assertThat(actual).contains("LogEntryResponse").contains(EXECUTION_ID).contains(PLUGIN_ID);
  }
}
