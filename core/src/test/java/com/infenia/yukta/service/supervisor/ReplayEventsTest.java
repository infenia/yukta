// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayEventsTest {

  @Test
  void aRunBecomesItsStatusEvents() {
    final List<ExecutionEvent> events =
        ReplayEvents.from(
            List.of(
                ExecutionLogEvent.started("e", "s", "w", "v"),
                ExecutionLogEvent.finished("e", "s", "w", "FAILURE", Map.of("n", "FAILURE"))));

    assertThat(events).hasSize(3);
    assertThat(events.get(0))
        .isInstanceOfSatisfying(
            ExecutionEvent.WorkflowStatusChanged.class,
            changed -> assertThat(changed.status()).isEqualTo("RUNNING"));
    assertThat(events.get(1))
        .isInstanceOfSatisfying(
            ExecutionEvent.NodeStatusChanged.class,
            changed -> {
              assertThat(changed.nodeId()).isEqualTo("n");
              assertThat(changed.status()).isEqualTo("FAILURE");
            });
    assertThat(events.get(2))
        .isInstanceOfSatisfying(
            ExecutionEvent.WorkflowStatusChanged.class,
            changed -> assertThat(changed.status()).isEqualTo("FAILURE"));
  }

  @Test
  void controlEntriesAreNotEvents() {
    assertThat(ReplayEvents.from(List.of(ExecutionLogEvent.control(true, "e", "Pause", "reason"))))
        .isEmpty();
  }
}
