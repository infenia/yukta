// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.service.control.ExecutionScheduler.Admission;
import com.infenia.yukta.service.control.ExecutionScheduler.OnLimit;
import com.infenia.yukta.service.control.ExecutionScheduler.Permit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyMethods"})
class ExecutionSchedulerTest {

  private static ExecutionScheduler scheduler(
      final int global, final int perWorkflow, final int queue, final OnLimit onLimit) {
    return new ExecutionScheduler(global, perWorkflow, queue, onLimit, Duration.ofMillis(300));
  }

  private static Permit granted(final Admission admission) {
    return admission.permit().block(Duration.ofSeconds(2));
  }

  @Test
  void noLimits_admitsEverythingAtOnce() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 100, OnLimit.QUEUE);

    for (int i = 0; i < 50; i++) {
      assertThat(scheduler.acquire("s", "w").immediate()).isTrue();
    }
    assertThat(scheduler.running()).isEqualTo(50);
  }

  @Test
  void globalLimit_queuesTheOverflow_andAdmitsInOrderAsSlotsFree() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    final Permit first = granted(scheduler.acquire("s", "w1"));
    final Admission second = scheduler.acquire("s", "w2");
    final Admission third = scheduler.acquire("s", "w3");

    assertThat(second.immediate()).isFalse();
    assertThat(scheduler.running()).isEqualTo(1);
    assertThat(scheduler.queued()).isEqualTo(2);

    final AtomicBoolean secondGranted = new AtomicBoolean();
    final AtomicBoolean thirdGranted = new AtomicBoolean();
    second.permit().subscribe(_ -> secondGranted.set(true));
    third.permit().subscribe(_ -> thirdGranted.set(true));

    first.release();
    assertThat(secondGranted).isTrue();
    assertThat(thirdGranted).isFalse();
    assertThat(scheduler.queued()).isEqualTo(1);
  }

  @Test
  void perWorkflowLimit_letsOtherWorkflowsOvertakeBlockedOne() {
    final ExecutionScheduler scheduler = scheduler(0, 1, 10, OnLimit.QUEUE);
    final Permit firstOfA = granted(scheduler.acquire("s", "a"));
    final Admission secondOfA = scheduler.acquire("s", "a");
    final Admission firstOfB = scheduler.acquire("s", "b");

    assertThat(secondOfA.immediate()).isFalse();
    assertThat(firstOfB.immediate()).isTrue();
    firstOfA.release();
    assertThat(granted(secondOfA)).isNotNull();
  }

  @Test
  void rejectPolicy_refusesWhatDoesNotFit() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.REJECT);
    granted(scheduler.acquire("s", "w"));

    assertThatThrownBy(() -> scheduler.assertCanAccept("s", "w"))
        .isInstanceOf(ExecutionRejectedException.class)
        .hasMessage("Execution limit reached");
    StepVerifier.create(scheduler.acquire("s", "w").permit())
        .expectError(ExecutionRejectedException.class)
        .verify();
  }

  @Test
  void queueFull_refusesFurtherStarts() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 1, OnLimit.QUEUE);
    granted(scheduler.acquire("s", "w"));
    scheduler.acquire("s", "w");

    assertThatThrownBy(() -> scheduler.assertCanAccept("s", "w"))
        .hasMessage("Execution limit reached and the queue is full");
    StepVerifier.create(scheduler.acquire("s", "w").permit())
        .expectError(ExecutionRejectedException.class)
        .verify();
  }

  @Test
  void assertCanAccept_passesWhileThereIsRoomOrQueueSpace() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 1, OnLimit.QUEUE);

    scheduler.assertCanAccept("s", "w");
    granted(scheduler.acquire("s", "w"));
    scheduler.assertCanAccept("s", "w");
  }

  @Test
  void waitingStartThatIsCancelled_leavesTheQueue() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    final Permit first = granted(scheduler.acquire("s", "w"));
    final var waiting = scheduler.acquire("s", "w").permit().subscribe();

    waiting.dispose();

    assertThat(scheduler.queued()).isZero();
    first.release();
    assertThat(scheduler.running()).isZero();
  }

  @Test
  void slotGrantedJustBeforeTheCancel_isGivenBack() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    final Permit first = granted(scheduler.acquire("s", "w"));
    final BaseSubscriber<Permit> waiting =
        new BaseSubscriber<>() {
          @Override
          protected void hookOnSubscribe(final Subscription subscription) {
            // no demand yet: the grant is held back until the subscriber asks for it
          }
        };
    scheduler.acquire("s", "w").permit().subscribe(waiting);

    // the slot is granted to the waiter, then the waiter goes away before taking it
    first.release();
    assertThat(scheduler.running()).isEqualTo(1);
    waiting.cancel();

    assertThat(scheduler.running()).isZero();
  }

  @Test
  void releasingTwice_onlyFreesOneSlot() {
    final ExecutionScheduler scheduler = scheduler(2, 0, 10, OnLimit.QUEUE);
    final Permit one = granted(scheduler.acquire("s", "w"));
    granted(scheduler.acquire("s", "w"));

    one.release();
    one.release();

    assertThat(scheduler.running()).isEqualTo(1);
  }

  @Test
  void shutdown_refusesNewAndQueuedStarts_andWaitsForRunningOnes() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    scheduler.start();
    assertThat(scheduler.isRunning()).isTrue();
    final Permit running = granted(scheduler.acquire("s", "w"));
    final var queued = scheduler.acquire("s", "w").permit().materialize().toFuture();
    final AtomicBoolean stopped = new AtomicBoolean();

    scheduler.stop(() -> stopped.set(true));

    assertThat(scheduler.isRunning()).isFalse();
    assertThat(stopped).isFalse();
    assertThat(queued).isDone();
    assertThatThrownBy(() -> scheduler.assertCanAccept("s", "w"))
        .hasMessage("The server is shutting down");
    StepVerifier.create(scheduler.acquire("s", "w").permit())
        .expectErrorMessage("The server is shutting down")
        .verify();

    running.release();
    assertThat(stopped).isTrue();
  }

  @Test
  void shutdown_givesUpAfterTheTimeout() throws InterruptedException {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    granted(scheduler.acquire("s", "w"));
    final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

    scheduler.stop(done::countDown);

    assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void shutdown_withNothingRunning_completesImmediately() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    final AtomicBoolean stopped = new AtomicBoolean();

    scheduler.stop(() -> stopped.set(true));
    scheduler.stop();

    assertThat(stopped).isTrue();
    assertThat(scheduler.getPhase()).isPositive();
  }

  @Test
  void shutdown_waitsUntilTheLastRunningExecutionIsDone() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 10, OnLimit.QUEUE);
    final Permit one = granted(scheduler.acquire("s", "w"));
    final Permit two = granted(scheduler.acquire("s", "w"));
    final AtomicBoolean stopped = new AtomicBoolean();
    scheduler.stop(() -> stopped.set(true));

    one.release();
    assertThat(stopped).isFalse();

    two.release();
    assertThat(stopped).isTrue();
  }

  @Test
  void waiterCancelledAfterShutdownRefusedIt_hasNothingToGiveBack() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    granted(scheduler.acquire("s", "w"));
    final BaseSubscriber<Permit> waiting =
        new BaseSubscriber<>() {
          @Override
          protected void hookOnSubscribe(final Subscription subscription) {
            // no demand: the refusal is held back
          }
        };
    scheduler.acquire("s", "w").permit().subscribe(waiting);

    scheduler.stop(() -> {});
    waiting.cancel();

    assertThat(scheduler.queued()).isZero();
    assertThat(scheduler.running()).isEqualTo(1);
  }

  @Test
  void higherPriorityWaitingStartIsAdmittedFirst() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    final Permit first = granted(scheduler.acquire("s", "w0"));
    final Admission low = scheduler.acquire("s", "w1", 0);
    final Admission high = scheduler.acquire("s", "w2", 5);
    final AtomicBoolean lowGranted = new AtomicBoolean();
    final AtomicBoolean highGranted = new AtomicBoolean();
    low.permit().subscribe(_ -> lowGranted.set(true));
    high.permit().subscribe(_ -> highGranted.set(true));

    first.release();

    assertThat(highGranted).isTrue();
    assertThat(lowGranted).isFalse();
  }

  @Test
  void raisingTheLimitAtRunTimeAdmitsWaitingStarts() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    granted(scheduler.acquire("s", "w0"));
    final AtomicBoolean granted = new AtomicBoolean();
    scheduler.acquire("s", "w1").permit().subscribe(_ -> granted.set(true));
    assertThat(granted).isFalse();

    scheduler.setLimits(2, 0);

    assertThat(granted).isTrue();
    assertThat(scheduler.running()).isEqualTo(2);
  }

  @Test
  void priorityIsReadFromThePayload() {
    assertThat(ExecutionScheduler.priorityOf(java.util.Map.of(ExecutionScheduler.PRIORITY_KEY, 7)))
        .isEqualTo(7);
    assertThat(ExecutionScheduler.priorityOf(java.util.Map.of("yukta.priority", "x"))).isZero();
    assertThat(ExecutionScheduler.priorityOf(null)).isZero();
  }
}
