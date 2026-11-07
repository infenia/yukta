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
@SuppressWarnings("PMD.TooManyStaticImports")
class TaskTrackerServiceDefaultsTest {

  private static final String EXECUTION = "e1";
  private static final String LINE = "line";

  @Test
  void executionLogAppendDefaultDelegatesToAppendLog() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class, CALLS_REAL_METHODS);
    final Mono<Void> appended = Mono.empty();
    when(tracker.appendLog(EXECUTION, LINE)).thenReturn(appended);

    assertThat(tracker.append(EXECUTION, LINE)).isSameAs(appended);

    verify(tracker).appendLog(EXECUTION, LINE);
  }

  @Test
  void streamAwareEmitFallsBackToThePlainEmitForImplementationsThatKeepNoStream() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class, CALLS_REAL_METHODS);

    tracker.emitLogEvent(EXECUTION, "n1", LINE, true);

    verify(tracker).emitLogEvent(EXECUTION, "n1", LINE);
  }

  @Test
  void setInitiatorDefaultRecordsNothingAndCallsNothingElse() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class, CALLS_REAL_METHODS);

    tracker.setInitiator(EXECUTION, "yk check");

    verify(tracker).setInitiator(EXECUTION, "yk check");
    verifyNoMoreInteractions(tracker);
  }
}
