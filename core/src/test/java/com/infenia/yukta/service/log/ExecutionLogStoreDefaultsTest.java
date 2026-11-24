// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The default lookups of {@link ExecutionLogStore}, run over a plain list. */
class ExecutionLogStoreDefaultsTest {

  private static final String SUCCESS = "SUCCESS";

  private static ExecutionLogStore listBacked(final List<ExecutionLogEvent> events) {
    return new ExecutionLogStore() {
      @Override
      public void append(final ExecutionLogEvent event) {
        events.add(event);
      }

      @Override
      public Flux<ExecutionLogEvent> readAll() {
        return Flux.defer(() -> Flux.fromIterable(new ArrayList<>(events)));
      }

      @Override
      public Mono<Void> applyRecovery(
          final java.util.Set<String> dropped,
          final int schedulerKept,
          final List<ExecutionLogEvent> added) {
        return Mono.fromRunnable(() -> {
          events.removeIf(event -> dropped.contains(event.executionId()));
          events.addAll(added);
        });
      }
    };
  }

  @Test
  void forExecution_filtersToTheRun() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    final ExecutionLogStore store = listBacked(events);
    store.append(ExecutionLogEvent.started("e1", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.started("e2", "s2", "w2", "v1"));
    store.append(ExecutionLogEvent.finished("e1", "s1", "w1", SUCCESS, Map.of()));

    assertThat(store.forExecution("e1").collectList().block()).hasSize(2);
    assertThat(store.forExecution("unknown").collectList().block()).isEmpty();
  }

  @Test
  void header_comesFromTheRunStart_orIsEmptyWithoutOne() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    final ExecutionLogStore store = listBacked(events);
    store.append(ExecutionLogEvent.started("e1", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.finished("e2", "s2", "w2", SUCCESS, Map.of()));

    assertThat(store.header("e1").blockOptional())
        .contains(new ExecutionLogStore.RunHeader("s1", "w1"));
    assertThat(store.header("e2").blockOptional()).isEmpty();
  }

  @Test
  void ofType_keepsTheTypeAfterTheInstant() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    final ExecutionLogStore store = listBacked(events);
    store.append(ExecutionLogEvent.started("e1", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.finished("e1", "s1", "w1", SUCCESS, Map.of()));

    assertThat(
            store.ofType(ExecutionLogEvent.Type.RUN_STARTED, Instant.EPOCH).collectList().block())
        .hasSize(1);
    assertThat(
            store
                .ofType(ExecutionLogEvent.Type.RUN_STARTED, Instant.now().plusSeconds(60))
                .collectList()
                .block())
        .isEmpty();
  }

  @Test
  void newest_returnsTheNewestOfTheTypesFirstUpToTheLimit() {
    final var store = listBacked(new ArrayList<>());
    store.append(ExecutionLogEvent.started("e1", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.started("e2", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.finished("e1", "s1", "w1", SUCCESS, Map.of()));
    store.append(ExecutionLogEvent.started("e3", "s1", "w1", "v1"));
    final var started = java.util.Set.of(ExecutionLogEvent.Type.RUN_STARTED);

    assertThat(
            store
                .newest(started, null, 2)
                .map(ExecutionLogEvent::executionId)
                .collectList()
                .block())
        .containsExactly("e3", "e2");
    assertThat(
            store
                .newest(started, "e1", 5)
                .map(ExecutionLogEvent::executionId)
                .collectList()
                .block())
        .containsExactly("e1");
    assertThat(store.newest(started, "none", 5).collectList().block()).isEmpty();
  }
}
