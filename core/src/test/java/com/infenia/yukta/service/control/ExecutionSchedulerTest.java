// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.model.control.SchedulerLimits;
import com.infenia.yukta.service.control.ExecutionScheduler.Admission;
import com.infenia.yukta.service.control.ExecutionScheduler.OnLimit;
import com.infenia.yukta.service.control.ExecutionScheduler.Permit;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
@ExpectedErrorLog("dropped error: .*ErrorCallbackNotImplemented")
class ExecutionSchedulerTest {

  private static final String LATE = "late";

  private static ExecutionScheduler scheduler(
      final int global, final int perWorkflow, final int queue, final OnLimit onLimit) {
    return new ExecutionScheduler(global, perWorkflow, queue, onLimit, Duration.ofMillis(300));
  }

  private static Permit granted(final Admission admission) {
    return admission.permit().block(Duration.ofSeconds(2));
  }

  @Test
  void childRunsAreBoundedByTheChildLimit() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 0, ExecutionScheduler.OnLimit.REJECT);
    org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "maxChildren", 2);

    final ExecutionScheduler.Admission first = scheduler.borrowed();
    scheduler.borrowed();
    assertThat(scheduler.borrowed().refused()).isTrue();
    assertThat(scheduler.childrenAlive()).isEqualTo(2);

    Objects.requireNonNull(first.permit().block()).release();
    assertThat(scheduler.borrowed().refused()).isFalse();
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
  void limits_reportsTheLimitsInForceAndTheSlotsInUse() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    final Permit running = granted(scheduler.acquire("s", "w"));
    scheduler.acquire("s", "w");

    assertThat(scheduler.limits()).isEqualTo(new SchedulerLimits(1, 0, 1, 1, 10));

    scheduler.setLimits(5, 2);
    assertThat(scheduler.limits()).isEqualTo(new SchedulerLimits(5, 2, 2, 0, 10));
    running.release();
  }

  @Test
  void startRate_refusesStartsOverTheLimitPerWorkflow_andCountsThem() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 10, OnLimit.QUEUE);
    scheduler.setMaxStartsPerMinute(2);

    scheduler.assertCanAccept("s", "w");
    scheduler.assertCanAccept("s", "w");
    scheduler.assertCanAccept("s", "other");

    assertThatThrownBy(() -> scheduler.assertCanAccept("s", "w"))
        .isInstanceOf(ExecutionRejectedException.class)
        .hasMessage("Too many starts of this workflow per minute");
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.RATE_LIMIT)).isEqualTo(1);
  }

  @Test
  void startRate_isPerSession_soTwoSessionsDoNotShareBucket() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 10, OnLimit.QUEUE);
    scheduler.setMaxStartsPerMinute(1);

    scheduler.assertCanAccept("s1", "build");

    org.assertj.core.api.Assertions.assertThatCode(() -> scheduler.assertCanAccept("s2", "build"))
        .doesNotThrowAnyException();
  }

  @Test
  void startRate_countsRestartsAndResumesWithNewStarts() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 10, OnLimit.QUEUE);
    scheduler.setMaxStartsPerMinute(2);

    scheduler.assertCanAccept("s", "w");
    assertThat(scheduler.acquireRerun("s", "w", 0, "e1").refused()).isFalse();
    final Admission third = scheduler.acquireRerun("s", "w", 0, "e2");

    assertThat(third.refused()).isTrue();
    StepVerifier.create(third.permit()).expectError(ExecutionRejectedException.class).verify();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.RATE_LIMIT)).isEqualTo(1);
    assertThat(scheduler.acquireRerun("s", "other", 0, "e3").refused()).isFalse();
  }

  @Test
  void queuedStart_givesUpAfterTheQueueTimeout_andFreesItsPlace() {
    final ExecutionScheduler scheduler =
        new ExecutionScheduler(
            1, 0, 10, OnLimit.QUEUE, Duration.ofMillis(300), Duration.ofMillis(100));
    final Permit first = granted(scheduler.acquire("s", "w"));

    StepVerifier.create(scheduler.acquire("s", "w").permit())
        .expectError(QueueTimeoutException.class)
        .verify(Duration.ofSeconds(2));

    assertThat(scheduler.queued()).isZero();
    first.release();
    assertThat(scheduler.running()).isZero();
  }

  @Test
  void queuedStart_thatIsGrantedInTime_isNotTimedOut() {
    final ExecutionScheduler scheduler =
        new ExecutionScheduler(
            1, 0, 10, OnLimit.QUEUE, Duration.ofMillis(300), Duration.ofSeconds(5));
    final Permit first = granted(scheduler.acquire("s", "w"));
    final var waiting = scheduler.acquire("s", "w").permit().toFuture();

    first.release();

    assertThat(waiting).succeedsWithin(Duration.ofSeconds(1));
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
  void start_afterStop_admitsExecutionsAgain() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);

    scheduler.stop();
    assertThat(scheduler.isRunning()).isFalse();
    scheduler.start();

    assertThat(scheduler.isRunning()).isTrue();
    assertThat(granted(scheduler.acquire("s", "w"))).isNotNull();
  }

  @Test
  void shutdownAfterRestartCycle_stillWaitsForRunningExecutions() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 10, OnLimit.QUEUE);
    scheduler.stop();
    scheduler.start();
    final Permit running = granted(scheduler.acquire("s", "w"));
    final AtomicBoolean stopped = new AtomicBoolean();

    scheduler.stop(() -> stopped.set(true));
    assertThat(stopped).isFalse();

    running.release();
    assertThat(stopped).isTrue();
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

  @Test
  void grantSubscriberStartingAnotherRun_doesNotBreakQueue() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    granted(scheduler.acquire("s", "w0"));
    scheduler.acquire("s", "w1").permit().subscribe(_ -> scheduler.acquire("s", "w2"));
    scheduler.acquire("s", "w3");

    scheduler.setLimits(3, 0);

    assertThat(scheduler.running()).isEqualTo(3);
    assertThat(scheduler.queued()).isEqualTo(1);
  }

  @Test
  void setPriority_movesWaitingStartAheadOfLowerPriorityOnes() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    final Permit first = granted(scheduler.acquire("s", "w"));
    final var order = new java.util.ArrayList<String>();
    scheduler.acquire("s", "w", 0, "low").permit().subscribe(p -> order.add("low"));
    scheduler.acquire("s", "w", 0, LATE).permit().subscribe(p -> order.add(LATE));

    assertThat(scheduler.changePriority(LATE, 5)).isTrue();
    first.release();

    assertThat(order).containsExactly(LATE);
  }

  @Test
  void setPriority_ofRunningOrUnknownExecution_isFalse_andCancelledMovedWaiterLeavesQueue() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    granted(scheduler.acquire("s", "w", 0, "running"));
    final var waiting = scheduler.acquire("s", "w", 0, "w1").permit().subscribe();

    assertThat(scheduler.changePriority("running", 9)).isFalse();
    assertThat(scheduler.changePriority("ghost", 9)).isFalse();
    assertThat(scheduler.changePriority("w1", 9)).isTrue();
    waiting.dispose();

    assertThat(scheduler.queued()).isZero();
  }

  private static ExecutionScheduler governed(
      final int global, final int perSession, final int workflowQueue, final int queue) {
    return new ExecutionScheduler(
        global,
        0,
        queue,
        OnLimit.QUEUE,
        Duration.ofMillis(300),
        Duration.ZERO,
        perSession,
        workflowQueue);
  }

  @Test
  void perSessionLimit_holdsBackOneSession_whileOthersStillRun() {
    final ExecutionScheduler scheduler = governed(0, 1, 0, 10);
    granted(scheduler.acquire("a", "w1"));

    final Admission second = scheduler.acquire("a", "w2");
    final Admission other = scheduler.acquire("b", "w1");

    assertThat(second.immediate()).isFalse();
    assertThat(other.immediate()).isTrue();
    assertThat(scheduler.queued()).isEqualTo(1);
  }

  @Test
  void freedSlot_goesToSessionWithFewestRunning_beforeOldestWaiter() {
    final ExecutionScheduler scheduler = governed(2, 0, 0, 10);
    final Permit firstOfA = granted(scheduler.acquire("a", "w"));
    granted(scheduler.acquire("a", "w"));
    final var order = new java.util.ArrayList<String>();
    scheduler.acquire("a", "w").permit().subscribe(p -> order.add("a"));
    scheduler.acquire("b", "w").permit().subscribe(p -> order.add("b"));

    firstOfA.release();

    assertThat(order).containsExactly("b");
  }

  @Test
  void priority_stillBeatsFairness() {
    final ExecutionScheduler scheduler = governed(1, 0, 0, 10);
    final Permit first = granted(scheduler.acquire("a", "w"));
    final var order = new java.util.ArrayList<String>();
    scheduler.acquire("b", "w", 0).permit().subscribe(p -> order.add("b-low"));
    scheduler.acquire("a", "w", 5).permit().subscribe(p -> order.add("a-high"));

    first.release();

    assertThat(order).containsExactly("a-high");
  }

  @Test
  void workflowQueueCapacity_refusesOnlyThatWorkflow_andCountsTheReason() {
    final ExecutionScheduler scheduler = governed(1, 0, 1, 10);
    granted(scheduler.acquire("s", "w"));
    scheduler.acquire("s", "w");

    assertThatThrownBy(() -> scheduler.assertCanAccept("s", "w"))
        .isInstanceOf(ExecutionRejectedException.class)
        .hasMessageContaining("queue of this workflow is full");
    StepVerifier.create(scheduler.acquire("s", "w").permit())
        .expectError(ExecutionRejectedException.class)
        .verify();
    scheduler.assertCanAccept("s", "other");

    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.WORKFLOW_QUEUE_FULL))
        .isEqualTo(2);
  }

  @Test
  void rejections_areCountedByReason() {
    final ExecutionScheduler reject = scheduler(1, 0, 10, OnLimit.REJECT);
    granted(reject.acquire("s", "w"));
    reject.acquire("s", "w").permit().subscribe(p -> {}, e -> {});
    final ExecutionScheduler full = scheduler(1, 0, 0, OnLimit.QUEUE);
    granted(full.acquire("s", "w"));
    full.acquire("s", "w").permit().subscribe(p -> {}, e -> {});
    full.stop();
    full.acquire("s", "w").permit().subscribe(p -> {}, e -> {});

    assertThat(reject.rejected(ExecutionScheduler.RejectReason.LIMIT)).isEqualTo(1);
    assertThat(full.rejected(ExecutionScheduler.RejectReason.QUEUE_FULL)).isEqualTo(1);
    assertThat(full.rejected(ExecutionScheduler.RejectReason.SHUTDOWN)).isEqualTo(1);
  }

  @Test
  void queueTimeout_isCounted() {
    final ExecutionScheduler scheduler =
        new ExecutionScheduler(
            1, 0, 10, OnLimit.QUEUE, Duration.ofMillis(300), Duration.ofMillis(50));
    granted(scheduler.acquire("s", "w"));

    StepVerifier.create(scheduler.acquire("s", "w").permit())
        .expectError(QueueTimeoutException.class)
        .verify(Duration.ofSeconds(2));

    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.QUEUE_TIMEOUT)).isEqualTo(1);
  }

  @Test
  void waitingStartsOfLowerPriorityDoNotOvertakeTheBestOne() {
    final ExecutionScheduler scheduler = scheduler(1, 0, 10, OnLimit.QUEUE);
    final Permit first = granted(scheduler.acquire("s", "w0"));
    final var order = new java.util.ArrayList<String>();
    scheduler.acquire("s", "w1", 5).permit().subscribe(_ -> order.add("high"));
    scheduler.acquire("s", "w2", 1).permit().subscribe(_ -> order.add("low"));

    first.release();

    assertThat(order).containsExactly("high");
  }

  @Test
  void standby_refusesEveryStart() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 100, OnLimit.QUEUE);
    scheduler.setLeaderElection(
        new com.infenia.yukta.service.leader.LeaderElection() {
          @Override
          public boolean isLeader() {
            return false;
          }
        });

    assertThatThrownBy(() -> scheduler.assertCanAccept("s", "w"))
        .isInstanceOf(com.infenia.yukta.service.leader.NotLeaderException.class);
    assertThatThrownBy(() -> scheduler.acquire("s", "w"))
        .isInstanceOf(com.infenia.yukta.service.leader.NotLeaderException.class);
    assertThat(scheduler.running()).isZero();
  }

  @Test
  void draining_refusesNewStarts_keepsRunningOnes_andCanBeUndone() {
    final ExecutionScheduler scheduler = scheduler(0, 0, 10, OnLimit.QUEUE);
    final Permit running = granted(scheduler.acquire("s", "w"));

    scheduler.setDraining(true);

    assertThat(scheduler.isDraining()).isTrue();
    assertThat(scheduler.running()).isEqualTo(1);
    assertThatThrownBy(() -> scheduler.assertCanAccept("s", "w"))
        .isInstanceOf(ExecutionRejectedException.class)
        .hasMessageContaining("draining");
    assertThat(scheduler.acquire("s", "w").refused()).isTrue();
    assertThat(scheduler.rejected(ExecutionScheduler.RejectReason.DRAINING)).isEqualTo(2);
    // a child run of a running parent still gets through
    assertThat(scheduler.borrowed().refused()).isFalse();

    running.release();
    scheduler.setDraining(false);
    assertThat(scheduler.acquire("s", "w").refused()).isFalse();
  }
}
