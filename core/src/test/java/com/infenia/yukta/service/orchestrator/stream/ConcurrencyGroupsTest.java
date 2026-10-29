// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import reactor.core.Disposable;

@NoArgsConstructor
class ConcurrencyGroupsTest {

  private static final String GRADLE = "gradle";

  private final ConcurrencyGroups groups = new ConcurrencyGroups(Map.of(GRADLE, 1));

  private ConcurrencyGroups.Place place() {
    return Objects.requireNonNull(groups.place(GRADLE));
  }

  private static StandardEnvironment isolatedEnvironment() {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    return environment;
  }

  @Test
  void limitsAreReadFromTheEnvironment() {
    final StandardEnvironment environment = isolatedEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("yukta.concurrency.groups.gradle", "1")));
    final ConcurrencyGroups bound = new ConcurrencyGroups(environment);

    assertThat(bound.place(GRADLE)).isNotNull();
    assertThat(bound.place("other")).isNull();
    assertThat(new ConcurrencyGroups(isolatedEnvironment()).place(GRADLE)).isNull();
  }

  @Test
  void groupWithoutLimit_hasNoPlace() {
    assertThat(groups.place(null)).isNull();
    assertThat(groups.place("other")).isNull();
    assertThat(new ConcurrencyGroups(Map.of("zero", 0)).place("zero")).isNull();
  }

  @Test
  void secondNode_waitsUntilTheFirstReleases_inArrivalOrder() {
    final AtomicInteger waited = new AtomicInteger();
    final AtomicInteger started = new AtomicInteger();
    final ConcurrencyGroups.Place first = place();
    final ConcurrencyGroups.Place second = place();
    final ConcurrencyGroups.Place third = place();

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
  void releaseIsIdempotent_andLeavingWaiterFreesItsTurn() {
    final AtomicInteger started = new AtomicInteger();
    final ConcurrencyGroups.Place first = place();
    final ConcurrencyGroups.Place waiting = place();
    final ConcurrencyGroups.Place last = place();
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
  void releasedBeforeAcquire_neverHoldsPlace() {
    final ConcurrencyGroups.Place skipped = place();
    skipped.release();
    final AtomicInteger started = new AtomicInteger();
    skipped.acquire(() -> {}).subscribe(null, null, started::incrementAndGet);
    final ConcurrencyGroups.Place next = place();
    next.acquire(() -> {}).subscribe(null, null, started::incrementAndGet);
    assertThat(started).hasValue(2);
  }
}
