// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@NoArgsConstructor
class TaskTrackerServiceDefaultsTest {

  @Test
  void executionLogAppendDefaultDelegatesToAppendLog() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class, CALLS_REAL_METHODS);
    final Mono<Void> appended = Mono.empty();
    when(tracker.appendLog("e1", "line")).thenReturn(appended);

    assertThat(tracker.append("e1", "line")).isSameAs(appended);

    verify(tracker).appendLog("e1", "line");
  }

  @Test
  void streamAwareEmitFallsBackToThePlainEmitForImplementationsThatKeepNoStream() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class, CALLS_REAL_METHODS);

    tracker.emitLogEvent("e1", "n1", "line", true);

    verify(tracker).emitLogEvent("e1", "n1", "line");
  }

  @Test
  void setInitiatorDefaultRecordsNothingAndCallsNothingElse() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class, CALLS_REAL_METHODS);

    tracker.setInitiator("e1", "yk check");

    verify(tracker).setInitiator("e1", "yk check");
    verifyNoMoreInteractions(tracker);
  }
}
