// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
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
}
