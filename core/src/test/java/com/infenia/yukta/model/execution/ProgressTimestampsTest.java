// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The start and end times of a run are server local times. They go out with the server's offset, so
 * a client in another time zone reads the right moment.
 */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ProgressTimestampsTest {

  /** Writes the DTOs the way the server does. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** A server local start time. */
  private static final LocalDateTime START = LocalDateTime.of(2026, 10, 16, 4, 26, 14, 183_000_000);

  /** The zone of the test JVM, restored after each test. */
  private TimeZone original;

  @BeforeEach
  void serverInIndia() {
    original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
  }

  @AfterEach
  void restoreZone() {
    TimeZone.setDefault(original);
  }

  private static JsonNode json(final Object value) {
    return MAPPER.readTree(MAPPER.writeValueAsString(value));
  }

  @Test
  void workflowProgressTimesCarryTheServersOffset() {
    final WorkflowProgress progress =
        new WorkflowProgress("e", "s", "w", "SUCCESS", List.of(), START, START.plusSeconds(5));

    final JsonNode node = json(progress);

    assertThat(node.get("startTime").asString()).isEqualTo("2026-10-16T04:26:14.183+05:30");
    assertThat(node.get("endTime").asString()).isEqualTo("2026-10-16T04:26:19.183+05:30");
    assertThat(OffsetDateTime.parse(node.get("startTime").asString()).toInstant().toString())
        .isEqualTo("2026-10-15T22:56:14.183Z");
  }

  @Test
  void missingEndTimeStaysNull() {
    final WorkflowProgress progress =
        new WorkflowProgress("e", "s", "w", "RUNNING", List.of(), START, null);

    assertThat(json(progress).get("endTime").isNull()).isTrue();
  }

  @Test
  void taskProgressTimesCarryTheServersOffset() {
    final TaskProgress task = new TaskProgress("n", "MAPPER", "RUNNING", START, null, Map.of());

    final JsonNode node = json(task);

    assertThat(node.get("startTime").asString()).endsWith("+05:30");
    assertThat(node.get("endTime").isNull()).isTrue();
  }

  @Test
  void executionSummaryTimesCarryTheServersOffset() {
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("e", "w", "SUCCESS", START, START.plusMinutes(1));

    final JsonNode node = json(summary);

    assertThat(node.get("startTime").asString()).isEqualTo("2026-10-16T04:26:14.183+05:30");
    assertThat(node.get("endTime").asString()).isEqualTo("2026-10-16T04:27:14.183+05:30");
  }
}
