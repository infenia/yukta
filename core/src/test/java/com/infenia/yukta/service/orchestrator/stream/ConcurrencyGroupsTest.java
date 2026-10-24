// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class ConcurrencyGroupsTest {

  private final ConcurrencyGroups groups = new ConcurrencyGroups(Map.of("gradle", 1));

  @Test
  void groupWithoutLimit_hasNoPlace() {
    assertThat(groups.place(null)).isNull();
    assertThat(groups.place("other")).isNull();
  }

  @Test
  void secondNode_waitsUntilTheFirstReleases_inArrivalOrder() {
    final AtomicInteger waited = new AtomicInteger();
    final AtomicInteger started = new AtomicInteger();
    final ConcurrencyGroups.Place first = groups.place("gradle");
    final ConcurrencyGroups.Place second = groups.place("gradle");
    final ConcurrencyGroups.Place third = groups.place("gradle");

    first.acquire(waited::incrementAndGet).subscribe(null, null, started::incrementAndGet);
    second.acquire(waited::incrementAndGet).subscribe(null, null, started::incrementAndGet);
    third.acquire(waited::incrementAndGet).subscribe(null, null, started::incrementAndGet);
    assertThat(started).hasValue(1);
    assertThat(waited).hasValue(2);

    first.release();
    assertThat(started).hasValue(2);
    second.release();
    assertThat(started).hasValue(3);
    third.release();
  }

  @Test
  void releaseIsIdempotent_andALeavingWaiterFreesItsTurn() {
    final AtomicInteger started = new AtomicInteger();
    final ConcurrencyGroups.Place first = groups.place("gradle");
    final ConcurrencyGroups.Place waiting = groups.place("gradle");
    final ConcurrencyGroups.Place last = groups.place("gradle");
    first.acquire(() -> {}).subscribe();
    final Disposable gone = waiting.acquire(() -> {}).subscribe();
    last.acquire(() -> {}).subscribe(null, null, started::incrementAndGet);

    gone.dispose();
    waiting.release();
    waiting.release();
    assertThat(started).hasValue(0);
    first.release();
    first.release();
    assertThat(started).hasValue(1);
  }

  @Test
  void releasedBeforeAcquire_neverHoldsAPlace() {
    final ConcurrencyGroups.Place skipped = groups.place("gradle");
    skipped.release();
    final AtomicInteger started = new AtomicInteger();
    skipped.acquire(() -> {}).subscribe(null, null, started::incrementAndGet);
    final ConcurrencyGroups.Place next = groups.place("gradle");
    next.acquire(() -> {}).subscribe(null, null, started::incrementAndGet);
    assertThat(started).hasValue(2);
  }
}
