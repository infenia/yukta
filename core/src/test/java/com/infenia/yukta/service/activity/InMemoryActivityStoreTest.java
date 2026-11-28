// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class InMemoryActivityStoreTest {

  private static final Instant T0 = Instant.parse("2026-11-28T10:00:00Z");
  private static final String WORKFLOW_STATUS = "WorkflowStatusChanged";
  private static final String ERROR = "ERROR";

  private static ActivityEntry entry(final String type, final String severity, final int n) {
    return new ActivityEntry(0, T0.plusSeconds(n), type, severity, "e" + n, "{\"n\":" + n + "}");
  }

  private static InMemoryActivityStore filled(final int capacity) {
    final InMemoryActivityStore store = new InMemoryActivityStore(capacity);
    store
        .append(
            List.of(
                entry(WORKFLOW_STATUS, "INFO", 1),
                entry("NodeStatusChanged", ERROR, 2),
                entry("ControlOutcome", "WARN", 3),
                entry(WORKFLOW_STATUS, ERROR, 4)))
        .block();
    return store;
  }

  private static List<String> ids(final Flux<ActivityEntry> found) {
    return found.map(ActivityEntry::executionId).collectList().block();
  }

  @Test
  void newestComesFirstAndTheStoreNumbersTheEntries() {
    final List<ActivityEntry> found =
        filled(10).newest(Set.of(), Set.of(), null, 10).collectList().block();

    assertThat(found)
        .extracting(ActivityEntry::executionId)
        .containsExactly("e4", "e3", "e2", "e1");
    assertThat(found).extracting(ActivityEntry::seq).containsExactly(4L, 3L, 2L, 1L);
    assertThat(found.getFirst().at()).isEqualTo(T0.plusSeconds(4));
    assertThat(found.getFirst().type()).isEqualTo(WORKFLOW_STATUS);
    assertThat(found.getFirst().severity()).isEqualTo(ERROR);
    assertThat(found.getFirst().json()).isEqualTo("{\"n\":4}");
  }

  @Test
  void keepsOnlyTheNewestCapacityEntries() {
    assertThat(ids(filled(2).newest(Set.of(), Set.of(), null, 10))).containsExactly("e4", "e3");
  }

  @Test
  void limitAndBeforePageBackwards() {
    final InMemoryActivityStore store = filled(10);

    assertThat(ids(store.newest(Set.of(), Set.of(), null, 1))).containsExactly("e4");
    assertThat(ids(store.newest(Set.of(), Set.of(), 4L, 10))).containsExactly("e3", "e2", "e1");
  }

  @Test
  void typeAndSeverityNarrowTheResult() {
    final InMemoryActivityStore store = filled(10);

    assertThat(ids(store.newest(Set.of(WORKFLOW_STATUS), Set.of(), null, 10)))
        .containsExactly("e4", "e1");
    assertThat(ids(store.newest(Set.of(), Set.of(ERROR), null, 10))).containsExactly("e4", "e2");
    assertThat(ids(store.newest(Set.of(WORKFLOW_STATUS), Set.of(ERROR), null, 10)))
        .containsExactly("e4");
    assertThat(ids(store.newest(Set.of("Unknown"), Set.of(), null, 10))).isEmpty();
  }

  @Test
  void anEmptyStoreAnswersEmptyAndAnEmptyAppendIsFine() {
    final InMemoryActivityStore store = new InMemoryActivityStore();
    store.append(List.of()).block();

    assertThat(ids(store.newest(Set.of(), Set.of(), null, 10))).isEmpty();
  }
}
