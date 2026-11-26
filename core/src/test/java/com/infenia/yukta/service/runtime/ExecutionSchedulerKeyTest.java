// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.control.OnLimit;
import com.infenia.yukta.service.ExecutionRejectedException;
import java.time.Duration;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Concurrency keys ({@code admission.concurrency}) in the scheduler. */
@NoArgsConstructor
class ExecutionSchedulerKeyTest {

  private static ExecutionScheduler.Claims key(final String key, final OnLimit overflow) {
    return new ExecutionScheduler.Claims(new ExecutionScheduler.KeyClaim(key, 1, overflow), null);
  }

  private static ExecutionScheduler unlimited(final OnLimit onLimit) {
    return ExecutionSchedulerFixture.create(0, 0, 10, onLimit, Duration.ZERO);
  }

  @Test
  void keyAtItsLimit_withReject_refusesOnlyThatKey() {
    final ExecutionScheduler scheduler = unlimited(OnLimit.QUEUE);

    final var first = scheduler.acquireStart("s", "w", 0, "e1", key("a", OnLimit.REJECT));
    final var second = scheduler.acquireStart("s", "w", 0, "e2", key("a", OnLimit.REJECT));
    final var other = scheduler.acquireStart("s", "w", 0, "e3", key("b", OnLimit.REJECT));

    assertThat(first.immediate()).isTrue();
    assertThat(second.refused()).isTrue();
    StepVerifier.create(second.permit())
        .expectErrorSatisfies(error ->
            assertThat(error)
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("key"))
        .verify();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.KEY_LIMIT)).isEqualTo(1);
    assertThat(other.immediate()).isTrue();
  }

  @Test
  void keyAtItsLimit_withQueue_waitsEvenWhenTheServerRejects_andGetsTheSlotWhenTheKeyFrees() {
    // on-limit REJECT is for the server's limits; a start held back only by its key follows the
    // key's overflow
    final ExecutionScheduler scheduler = unlimited(OnLimit.REJECT);
    final var first = scheduler.acquireStart("s", "w", 0, "e1", key("a", OnLimit.QUEUE));

    final var second = scheduler.acquireStart("s", "w", 0, "e2", key("a", OnLimit.QUEUE));

    assertThat(second.immediate()).isFalse();
    assertThat(second.refused()).isFalse();
    assertThat(scheduler.queued()).isEqualTo(1);
    Objects.requireNonNull(first.permit().block()).release();
    StepVerifier.create(second.permit()).expectNextCount(1).verifyComplete();
    assertThat(scheduler.queued()).isZero();
  }

  @Test
  void waitingStartOfFullKeyDoesNotHoldBackOtherKeysOrTheSameKeyOnAnotherWorkflow() {
    final ExecutionScheduler scheduler = unlimited(OnLimit.QUEUE);
    scheduler.acquireStart("s", "w", 0, "e1", key("a", OnLimit.QUEUE));
    scheduler.acquireStart("s", "w", 0, "e2", key("a", OnLimit.QUEUE));

    assertThat(scheduler.acquireStart("s", "w", 0, "e3", key("b", OnLimit.QUEUE)).immediate())
        .isTrue();
    assertThat(scheduler.acquireStart("s", "w2", 0, "e4", key("a", OnLimit.QUEUE)).immediate())
        .isTrue();
    assertThat(scheduler.acquireRerun("s", "w", 0, "e5", null).immediate()).isTrue();
  }

  @Test
  void keyWithRoomStillObeysTheServerLimits() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(1, 0, 10, OnLimit.REJECT, Duration.ZERO);
    scheduler.acquireStart("s", "w", 0, "e1", key("a", OnLimit.QUEUE));

    final var second = scheduler.acquireStart("s", "w", 0, "e2", key("b", OnLimit.QUEUE));

    assertThat(second.refused()).isTrue();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.LIMIT)).isEqualTo(1);
  }

  @Test
  void restartClaimsItsKeyLikeStartAndFullQueueRefusesWaitingKey() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(0, 0, 0, OnLimit.QUEUE, Duration.ZERO);
    final var restart = scheduler.acquireRerun("s", "w", 0, "e1", key("a", OnLimit.QUEUE));

    final var start = scheduler.acquireStart("s", "w", 0, "e2", key("a", OnLimit.QUEUE));

    assertThat(restart.immediate()).isTrue();
    assertThat(start.refused()).isTrue();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.QUEUE_FULL)).isEqualTo(1);
  }

  @Test
  void releasingOneOfTwoRunsOfOneKey_freesOneSlotForTheWaitingStart() {
    final ExecutionScheduler scheduler = unlimited(OnLimit.QUEUE);
    final ExecutionScheduler.Claims two =
        new ExecutionScheduler.Claims(new ExecutionScheduler.KeyClaim("a", 2, OnLimit.QUEUE), null);
    final var first = scheduler.acquireStart("s", "w", 0, "e1", two);
    final var second = scheduler.acquireStart("s", "w", 0, "e2", two);
    final var third = scheduler.acquireStart("s", "w", 0, "e3", two);

    assertThat(first.immediate()).isTrue();
    assertThat(second.immediate()).isTrue();
    assertThat(third.immediate()).isFalse();

    Objects.requireNonNull(first.permit().block()).release();

    StepVerifier.create(third.permit()).expectNextCount(1).verifyComplete();
    assertThat(scheduler.queued()).isZero();
  }
}
