// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionLogEventInitiatorTest {

  @Test
  void runStartRecordsWhoStartedIt() {
    final var payload = ExecutionLogEvent.withInitiator(Map.of("a", 1), "yk check");

    final var started = ExecutionLogEvent.started("e1", "s", "w", "v", payload);

    assertThat(started.initiator()).isEqualTo("yk check");
    assertThat(started.payload()).containsEntry("a", 1);
  }

  @Test
  void startWithoutAnInitiatorRecordsNone() {
    assertThat(ExecutionLogEvent.started("e1", "s", "w", "v").initiator()).isNull();
    assertThat(ExecutionLogEvent.started("e1", "s", "w", "v", Map.of("a", 1)).initiator()).isNull();
  }

  @Test
  void onlyNonBlankTextCountsAsAnInitiator() {
    final Map<String, Object> blank = Map.of(ExecutionLogEvent.INITIATOR_KEY, "  ");
    final Map<String, Object> number = Map.of(ExecutionLogEvent.INITIATOR_KEY, 7);

    assertThat(ExecutionLogEvent.initiatorOf(blank)).isNull();
    assertThat(ExecutionLogEvent.initiatorOf(number)).isNull();
    assertThat(ExecutionLogEvent.initiatorOf(null)).isNull();
  }

  @Test
  void callersOwnValueOfTheReservedKeyIsDropped() {
    final Map<String, Object> forged = Map.of(ExecutionLogEvent.INITIATOR_KEY, "root");

    assertThat(ExecutionLogEvent.withInitiator(forged, "key-a"))
        .containsEntry(ExecutionLogEvent.INITIATOR_KEY, "key-a");
    // with nobody known, the forged value goes and nothing replaces it
    assertThat(ExecutionLogEvent.withInitiator(forged, null))
        .doesNotContainKey(ExecutionLogEvent.INITIATOR_KEY);
    assertThat(ExecutionLogEvent.withInitiator(forged, " "))
        .doesNotContainKey(ExecutionLogEvent.INITIATOR_KEY);
    // the argument is not changed
    assertThat(forged).containsEntry(ExecutionLogEvent.INITIATOR_KEY, "root");
  }

  @Test
  void anEntryWrittenWithoutAnInitiatorReadsAsNone() {
    final var event =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_FINISHED,
            "e1",
            "s",
            "w",
            null,
            "SUCCESS",
            Map.of(),
            null,
            null,
            Instant.EPOCH,
            null);

    assertThat(event.initiator()).isNull();
  }

  @Test
  void externalPayloadCannotNameParentRun() {
    final var payload =
        ExecutionLogEvent.withInitiator(
            Map.of("yukta.parentExecutionId", "victim", "yukta.depth", 0, "a", 1), "k");

    assertThat(payload)
        .doesNotContainKeys("yukta.parentExecutionId", "yukta.depth")
        .containsEntry("a", 1);
  }
}
