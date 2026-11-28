// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which worker failed the last attempt of a node, and how a retry avoids it. */
class LastFailedTest {

  @Test
  @DisplayName("[T1] workerOf returns the last failed worker")
  void workerOf_afterFailure_returnsFailedWorker() {
    final LastFailed lastFailed = new LastFailed();
    lastFailed.failed("e", "n", "A");

    assertThat(lastFailed.workerOf("e", "n")).isEqualTo("A");
  }

  @Test
  @DisplayName("[T2] success forgets the failed worker")
  void workerOf_afterFailureThenSuccess_returnsNull() {
    final LastFailed lastFailed = new LastFailed();
    lastFailed.failed("e", "n", "A");
    lastFailed.succeeded("e", "n");

    assertThat(lastFailed.workerOf("e", "n")).isNull();
  }

  @Test
  @DisplayName("[T3] avoided worker goes last")
  void avoiding_avoidedWorkerFirst_movesAvoidedWorkerLast() {
    final List<String> ordered = List.of("A", "B", "C");

    final List<String> result = LastFailed.avoiding(ordered, id -> id, "A");

    assertThat(result).containsExactly("B", "C", "A");
  }

  @Test
  @DisplayName("[T4] single candidate is kept")
  void avoiding_singleAvoidedCandidate_keepsCandidate() {
    final List<String> ordered = List.of("A");

    final List<String> result = LastFailed.avoiding(ordered, id -> id, "A");

    assertThat(result).containsExactly("A");
  }
}
