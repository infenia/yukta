// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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
 * The start and end times of a run are instants. They go out in UTC (a trailing {@code Z}) whatever
 * the zone of the server, so a client in any zone reads the same moment.
 */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ProgressTimestampsTest {

  /** Writes the DTOs the way the server does. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Instant START = Instant.parse("2026-10-15T22:56:14.183Z");

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
  void workflowProgressTimesAreUtc() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            "e", "s", "w", TaskStatus.SUCCESS, List.of(), START, START.plus(Duration.ofSeconds(5)));

    final JsonNode node = json(progress);

    assertThat(node.get("startTime").asString()).isEqualTo("2026-10-15T22:56:14.183Z");
    assertThat(node.get("endTime").asString()).isEqualTo("2026-10-15T22:56:19.183Z");
    assertThat(node.get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void missingEndTimeStaysNull() {
    final WorkflowProgress progress =
        new WorkflowProgress("e", "s", "w", TaskStatus.RUNNING, List.of(), START, null);

    assertThat(json(progress).get("endTime").isNull()).isTrue();
  }

  @Test
  void taskProgressTimesAreUtc() {
    final TaskProgress task =
        new TaskProgress("n", "MAPPER", TaskStatus.RUNNING, START, null, Map.of());

    final JsonNode node = json(task);

    assertThat(node.get("startTime").asString()).endsWith("Z");
    assertThat(node.get("endTime").isNull()).isTrue();
  }

  @Test
  void executionSummaryTimesAreUtc() {
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "e", "w", TaskStatus.SUCCESS, START, START.plus(Duration.ofMinutes(1)));

    final JsonNode node = json(summary);

    assertThat(node.get("startTime").asString()).isEqualTo("2026-10-15T22:56:14.183Z");
    assertThat(node.get("endTime").asString()).isEqualTo("2026-10-15T22:57:14.183Z");
  }
}
