// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class LogRetentionTest {

  private static ExecutionLogEvent change(final String status) {
    return ExecutionLogEvent.control(true, ExecutionLogEvent.SCHEDULER, status, null);
  }

  @Test
  void schedulerChanges_areTrimmedEvenWhenTheRunCountIsWithinTheLimit() {
    final List<ExecutionLogEvent> events =
        List.of(change("SetLimits(1)"), change("SetLimits(2)"), change("SetLimits(3)"));

    assertThat(LogRetention.trim(events, 2))
        .extracting(ExecutionLogEvent::status)
        .containsExactly("SetLimits(2)", "SetLimits(3)");
  }

  @Test
  void logWithinTheLimits_isReturnedAsIs() {
    final List<ExecutionLogEvent> events = List.of(change("SetLimits(1)"));

    assertThat(LogRetention.trim(events, 2)).isSameAs(events);
  }

  @Test
  void keep_giveEverySessionItsNewestRuns_evenWhenABusySessionFillsTheGlobalLimit() {
    final List<String> runs = new java.util.ArrayList<>();
    runs.add("quiet-1");
    runs.add("quiet-2");
    for (int i = 0; i < 50; i++) {
      runs.add("busy-" + i);
    }

    final java.util.Set<String> keep =
        LogRetention.keep(runs, id -> id.startsWith("busy") ? "busy" : "quiet", 20);

    assertThat(keep).contains("quiet-1", "quiet-2", "busy-49", "busy-30");
    assertThat(keep).doesNotContain("busy-29");
    // the global 20 newest (all busy) plus the quiet session's 2
    assertThat(keep).hasSize(22);
  }
}
