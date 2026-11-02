// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The default lookups of {@link ExecutionLogStore}, run over a plain list. */
class ExecutionLogStoreDefaultsTest {

  private static ExecutionLogStore listBacked(final List<ExecutionLogEvent> events) {
    return new ExecutionLogStore() {
      @Override
      public void append(final ExecutionLogEvent event) {
        events.add(event);
      }

      @Override
      public List<ExecutionLogEvent> readAll() {
        return new ArrayList<>(events);
      }

      @Override
      public void replaceAll(final List<ExecutionLogEvent> kept) {
        events.clear();
        events.addAll(kept);
      }
    };
  }

  @Test
  void forExecution_filtersToTheRun() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    final ExecutionLogStore store = listBacked(events);
    store.append(ExecutionLogEvent.started("e1", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.started("e2", "s2", "w2", "v1"));
    store.append(ExecutionLogEvent.finished("e1", "s1", "w1", "SUCCESS", Map.of()));

    assertThat(store.forExecution("e1")).hasSize(2);
    assertThat(store.forExecution("unknown")).isEmpty();
  }

  @Test
  void header_comesFromTheRunStart_orIsEmptyWithoutOne() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    final ExecutionLogStore store = listBacked(events);
    store.append(ExecutionLogEvent.started("e1", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.finished("e2", "s2", "w2", "SUCCESS", Map.of()));

    assertThat(store.header("e1")).contains(new ExecutionLogStore.RunHeader("s1", "w1"));
    assertThat(store.header("e2")).isEmpty();
  }

  @Test
  void ofType_keepsTheTypeAfterTheInstant() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    final ExecutionLogStore store = listBacked(events);
    store.append(ExecutionLogEvent.started("e1", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.finished("e1", "s1", "w1", "SUCCESS", Map.of()));

    assertThat(store.ofType(ExecutionLogEvent.Type.RUN_STARTED, Instant.EPOCH)).hasSize(1);
    assertThat(store.ofType(ExecutionLogEvent.Type.RUN_STARTED, Instant.now().plusSeconds(60)))
        .isEmpty();
  }
}
