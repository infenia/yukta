// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.control.OnLimit;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Throttles ({@code admission.throttle}) in the scheduler. */
@NoArgsConstructor
class ExecutionSchedulerThrottleTest {

  private static ExecutionScheduler.Claims throttled(final String key, final Duration period) {
    return new ExecutionScheduler.Claims(
        null, new ExecutionScheduler.ThrottleClaim(key, 1, period));
  }

  @Test
  void startOverTheThrottle_waitsEvenWhenTheServerRejects_andIsAdmittedWhenThePeriodHasRoom() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(0, 0, 10, OnLimit.REJECT, Duration.ZERO);
    final Duration period = Duration.ofMillis(200);

    final var first = scheduler.acquireStart("s", "w", 0, "e1", throttled("a", period));
    final var second = scheduler.acquireStart("s", "w", 0, "e2", throttled("a", period));
    final var otherKey = scheduler.acquireStart("s", "w", 0, "e3", throttled("b", period));

    assertThat(first.immediate()).isTrue();
    assertThat(second.immediate()).isFalse();
    assertThat(second.refused()).isFalse();
    assertThat(otherKey.immediate()).isTrue();
    // nothing ends: only the timer can let the waiting start in, once the first leaves the period
    StepVerifier.create(second.permit())
        .expectNextCount(1)
        .expectComplete()
        .verify(Duration.ofSeconds(5));
    assertThat(scheduler.queued()).isZero();
  }

  @Test
  void throttleAndConcurrencyKey_bothApply() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(0, 0, 10, OnLimit.QUEUE, Duration.ZERO);
    final ExecutionScheduler.Claims both =
        new ExecutionScheduler.Claims(
            new ExecutionScheduler.KeyClaim("k", 1, OnLimit.REJECT),
            new ExecutionScheduler.ThrottleClaim("", 5, Duration.ofMinutes(1)));

    final var first = scheduler.acquireStart("s", "w", 0, "e1", both);
    final var second = scheduler.acquireStart("s", "w", 0, "e2", both);

    assertThat(first.immediate()).isTrue();
    assertThat(second.refused()).isTrue();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.KEY_LIMIT)).isEqualTo(1);
  }

  @Test
  void throttleAndQueuedConcurrencyKey_waitInsteadOfBeingRefused() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(0, 0, 10, OnLimit.QUEUE, Duration.ZERO);
    final ExecutionScheduler.Claims both =
        new ExecutionScheduler.Claims(
            new ExecutionScheduler.KeyClaim("k", 1, OnLimit.QUEUE),
            new ExecutionScheduler.ThrottleClaim("", 5, Duration.ofMinutes(1)));

    final var first = scheduler.acquireStart("s", "w", 0, "e1", both);
    final var second = scheduler.acquireStart("s", "w", 0, "e2", both);

    assertThat(first.immediate()).isTrue();
    assertThat(second.immediate()).isFalse();
    assertThat(second.refused()).isFalse();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.KEY_LIMIT)).isZero();
    assertThat(scheduler.queued()).isEqualTo(1);
  }

  @Test
  void throttleFullWhileConcurrencyKeyHasRoom_waitsForTheThrottle() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(0, 0, 10, OnLimit.REJECT, Duration.ZERO);
    final ExecutionScheduler.Claims both =
        new ExecutionScheduler.Claims(
            new ExecutionScheduler.KeyClaim("k", 5, OnLimit.REJECT),
            new ExecutionScheduler.ThrottleClaim("", 1, Duration.ofMinutes(1)));

    final var first = scheduler.acquireStart("s", "w", 0, "e1", both);
    final var second = scheduler.acquireStart("s", "w", 0, "e2", both);

    assertThat(first.immediate()).isTrue();
    assertThat(second.immediate()).isFalse();
    assertThat(second.refused()).isFalse();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.KEY_LIMIT)).isZero();
  }

  @Test
  void throttledStartWithFullQueue_isRefused() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(0, 0, 0, OnLimit.QUEUE, Duration.ZERO);
    scheduler.acquireStart("s", "w", 0, "e1", throttled("", Duration.ofMinutes(1)));

    final var second =
        scheduler.acquireStart("s", "w", 0, "e2", throttled("", Duration.ofMinutes(1)));

    assertThat(second.refused()).isTrue();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.QUEUE_FULL)).isEqualTo(1);
  }
}
