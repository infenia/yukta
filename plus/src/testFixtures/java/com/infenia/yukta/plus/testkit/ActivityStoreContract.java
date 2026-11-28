// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.activity.ActivityEntry;
import com.infenia.yukta.service.activity.ActivityStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ActivityStore} must do. Extend it in the test of an implementation and return a
 * store that starts empty from {@link #create()}.
 */
public abstract class ActivityStoreContract {

  private static final Instant T0 = Instant.parse("2026-11-28T10:00:00Z");

  /** Creates the contract. */
  protected ActivityStoreContract() {
    super();
  }

  /**
   * A new, empty store.
   *
   * @return the store
   */
  protected abstract ActivityStore create();

  private static ActivityEntry entry(final String type, final String severity, final int n) {
    return new ActivityEntry(0, T0.plusSeconds(n), type, severity, "e" + n, "{\"n\":" + n + "}");
  }

  private static ActivityStore filled(final ActivityStore store) {
    store
        .append(
            List.of(
                entry("WorkflowStatusChanged", "INFO", 1),
                entry("NodeStatusChanged", "ERROR", 2),
                entry("ControlOutcome", "WARN", 3),
                entry("WorkflowStatusChanged", "ERROR", 4)))
        .block();
    return store;
  }

  @Test
  void newestComesFirstAndTheStoreNumbersTheEntries() {
    final var found = filled(create()).newest(Set.of(), Set.of(), null, 10).collectList().block();

    assertThat(found)
        .extracting(ActivityEntry::executionId)
        .containsExactly("e4", "e3", "e2", "e1");
    assertThat(found)
        .extracting(ActivityEntry::seq)
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    assertThat(found.getFirst().json()).contains("\"n\"").contains("4");
    assertThat(found.getFirst().at()).isEqualTo(T0.plusSeconds(4));
  }

  @Test
  void limitAndBeforePageBackwards() {
    final ActivityStore store = filled(create());
    final var first = store.newest(Set.of(), Set.of(), null, 2).collectList().block();
    final var second =
        store.newest(Set.of(), Set.of(), first.getLast().seq(), 2).collectList().block();

    assertThat(first).extracting(ActivityEntry::executionId).containsExactly("e4", "e3");
    assertThat(second).extracting(ActivityEntry::executionId).containsExactly("e2", "e1");
  }

  @Test
  void typeAndSeverityNarrowTheResult() {
    final ActivityStore store = filled(create());

    assertThat(
            store.newest(Set.of("WorkflowStatusChanged"), Set.of(), null, 10).collectList().block())
        .extracting(ActivityEntry::executionId)
        .containsExactly("e4", "e1");
    assertThat(store.newest(Set.of(), Set.of("ERROR", "WARN"), null, 10).collectList().block())
        .extracting(ActivityEntry::executionId)
        .containsExactly("e4", "e3", "e2");
    assertThat(
            store
                .newest(Set.of("WorkflowStatusChanged"), Set.of("ERROR"), null, 10)
                .collectList()
                .block())
        .extracting(ActivityEntry::executionId)
        .containsExactly("e4");
  }

  @Test
  void anEmptyStoreAnswersEmptyAndAnEmptyAppendIsFine() {
    final ActivityStore store = create();
    store.append(List.of()).block();

    assertThat(store.newest(Set.of(), Set.of(), null, 10).collectList().block()).isEmpty();
  }
}
