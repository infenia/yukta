// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@NoArgsConstructor
class BatchesTest {

  @Test
  void fullBatchesRunAgain_untilOneIsShort_andTheCountsAreSummed() {
    final Deque<Long> deleted =
        new ArrayDeque<>(List.of((long) Batches.SIZE, (long) Batches.SIZE, 7L, 99L));

    assertThat(Batches.repeat(size -> Mono.just(deleted.pop())).block())
        .isEqualTo(2L * Batches.SIZE + 7);
    // the batch after the short one never ran
    assertThat(deleted).containsExactly(99L);
  }

  @Test
  void anEmptyFirstBatchStopsAtOnce() {
    assertThat(Batches.repeat(size -> Mono.just(0L)).block()).isZero();
  }
}
