// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class InMemoryExecutionLogStoreTest {

  private static void run(final InMemoryExecutionLogStore store, final String id) {
    store.append(ExecutionLogEvent.started(id, "s", "w", "v"));
    store.append(ExecutionLogEvent.finished(id, "s", "w", "SUCCESS", Map.of()));
  }

  @Test
  void trimsToTheNewestRuns_andKeepsARunWithoutAnEnd() {
    final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore(2, 9);
    store.append(ExecutionLogEvent.started("open", "s", "w", "v"));
    for (int i = 0; i < 4; i++) {
      run(store, "r" + i);
    }

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::executionId)
        .containsOnly("open", "r2", "r3");
  }

  @Test
  void neverGrowsPastTheBoundForManyRuns() {
    final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore(5, 20);
    for (int i = 0; i < 1000; i++) {
      run(store, "r" + i);
    }

    assertThat(store.readAll().size()).isLessThan(40);
  }
}
