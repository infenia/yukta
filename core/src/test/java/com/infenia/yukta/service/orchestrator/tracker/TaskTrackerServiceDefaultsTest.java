// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class TaskTrackerServiceDefaultsTest {

  @Test
  void streamAwareEmitFallsBackToThePlainEmitForImplementationsThatKeepNoStream() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class, CALLS_REAL_METHODS);

    tracker.emitLogEvent("e1", "n1", "line", true);

    verify(tracker).emitLogEvent("e1", "n1", "line");
  }
}
