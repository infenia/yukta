// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports"
})
@ExpectedErrorLog("ERROR com.infenia.yukta.service.orchestrator.stream.NodeStreams - ")
class NodeStreamsTest {

  private static final String EXEC = "exec-1";
  private static final String NODE_ID = "node-1";
  private static final String TASK = "type";
  private static final String BOOM = "boom";

  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final WorkflowNode node = new WorkflowNode(NODE_ID, "type", Map.of());

  private static Flux<Message<?>> emitAfter(final Duration delay, final Message<?> msg) {
    return Mono.delay(delay).flatMapMany(_ -> Flux.<Message<?>>just(msg));
  }

  private Flux<Message<?>> decorate(final Flux<Message<?>> source) {
    return decorate(source, new NodeActivity(), () -> false, Duration.ofMillis(200));
  }

  private Flux<Message<?>> decorate(
      final Flux<Message<?>> source,
      final NodeActivity activity,
      final BooleanSupplier paused,
      final Duration timeout) {
    return NodeStreams.withTimeoutAndTracking(
        node, timeout, tracker, EXEC, source, activity, paused, null, null);
  }

  private Flux<Message<?>> decorateWithStall(
      final NodeActivity activity, final Duration stallAfter) {
    return NodeStreams.withTimeoutAndTracking(
        node,
        Duration.ofSeconds(5),
        tracker,
        EXEC,
        Flux.<Message<?>>never(),
        activity,
        () -> false,
        null,
        stallAfter);
  }

  private void expectStalledWarnings(final int times) {
    verify(tracker, times(times))
        .emitLogEvent(
            eq(EXEC),
            eq(NODE_ID),
            startsWith(NodeStreams.STALLED_PREFIX),
            eq(false),
            eq(LogLevel.WARN));
  }

  private void expectStatus(final TaskStatus status, final int times) {
    verify(tracker, times(times))
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(status), anyMap());
  }

  @Test
  void running_isReportedAtFirstActivity_notAtSubscribe() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x");
    final NodeActivity activity = new NodeActivity();

    StepVerifier.create(
            decorate(Flux.<Message<?>>never(), activity, () -> false, Duration.ofSeconds(5)))
        .expectSubscription()
        .then(() -> expectStatus(TaskStatus.RUNNING, 0))
        .then(activity::touch)
        .then(activity::touch)
        .then(() -> expectStatus(TaskStatus.RUNNING, 1))
        .thenCancel()
        .verify();

    expectStatus(TaskStatus.RUNNING, 1);
    expectStatus(TaskStatus.CANCELLED, 1);
    assertThat(msg).isNotNull();
  }

  @Test
  void nodeThatEndsWithoutActivity_isSkipped_notSuccess() {
    StepVerifier.create(decorate(Flux.empty())).verifyComplete();

    final InOrder order = inOrder(tracker);
    order
        .verify(tracker)
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.RUNNING), anyMap());
    order
        .verify(tracker)
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.SKIPPED), anyMap());
    expectStatus(TaskStatus.SUCCESS, 0);
  }

  @Test
  void nodeMetadataOfAMessage_isMergedIntoTheSuccessEvent() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), "x")
            .withMetadata(Map.of(Message.NODE_METADATA_KEY, Map.of("cost", 0.5)));

    StepVerifier.create(decorate(Flux.just(msg))).expectNext(msg).verifyComplete();

    verify(tracker)
        .emitTaskStatusEvent(
            eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.SUCCESS), eq(Map.of("cost", 0.5)));
  }

  @Test
  void success_reportsRunningThenSuccess_andPassesMessagesThrough() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x");

    StepVerifier.create(decorate(Flux.just(msg))).expectNext(msg).verifyComplete();

    final InOrder order = inOrder(tracker);
    order
        .verify(tracker)
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.RUNNING), anyMap());
    order
        .verify(tracker)
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.SUCCESS), anyMap());
    expectStatus(TaskStatus.FAILURE, 0);
  }

  @Test
  void error_reportsFailureExactlyOnce() {
    StepVerifier.create(decorate(Flux.error(new IllegalStateException(BOOM))))
        .expectError(IllegalStateException.class)
        .verify();

    expectStatus(TaskStatus.RUNNING, 1);
    expectStatus(TaskStatus.FAILURE, 1);
    expectStatus(TaskStatus.SUCCESS, 0);
  }

  @Test
  void stalledStream_timesOutAfterItsLastOutputAndReportsFailure() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x");

    StepVerifier.create(decorate(Flux.concat(Flux.just(msg), Flux.never())))
        .expectNext(msg)
        .expectError(TimeoutException.class)
        .verify();

    expectStatus(TaskStatus.TIMED_OUT, 1);
    expectStatus(TaskStatus.FAILURE, 0);
  }

  @Test
  void nothingIsReportedUntilSubscribed() {
    decorate(Flux.empty());

    verify(tracker, never())
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.RUNNING), anyMap());
  }

  @Test
  void waitingForUpstream_doesNotCountTowardsTheTimeout() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x");
    final AtomicBoolean never = new AtomicBoolean();

    StepVerifier.withVirtualTime(
            () -> {
              final NodeActivity activity = new NodeActivity();
              // input reaches this node only after 2 s; its 1 s timeout must not have run meanwhile
              Schedulers.parallel().schedule(activity::touch, 2, TimeUnit.SECONDS);
              final Flux<Message<?>> output = emitAfter(Duration.ofMillis(2500), msg);
              return decorate(output, activity, never::get, Duration.ofSeconds(1));
            })
        .expectSubscription()
        .thenAwait(Duration.ofMillis(2500))
        .expectNext(msg)
        .verifyComplete();
  }

  @Test
  void idleAfterFirstActivity_timesOutOnTime() {
    StepVerifier.withVirtualTime(
            () -> {
              final NodeActivity activity = new NodeActivity();
              activity.touch();
              return decorate(Flux.never(), activity, () -> false, Duration.ofSeconds(1));
            })
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(900))
        .thenAwait(Duration.ofMillis(100))
        .expectError(TimeoutException.class)
        .verify();
  }

  @Test
  void pause_holdsTheClock_andAnIdleNodeStillTimesOutAfterResume() {
    final AtomicBoolean paused = new AtomicBoolean(true);

    StepVerifier.withVirtualTime(
            () -> {
              final NodeActivity activity = new NodeActivity();
              activity.touch();
              return decorate(Flux.never(), activity, paused::get, Duration.ofSeconds(1));
            })
        .expectSubscription()
        .expectNoEvent(Duration.ofMinutes(2))
        .then(() -> paused.set(false))
        .thenAwait(Duration.ofSeconds(1))
        .expectError(TimeoutException.class)
        .verify();
  }

  @Test
  void pauseLongerThanTheTimeout_doesNotFailAnActiveNode() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x");
    final AtomicBoolean paused = new AtomicBoolean(true);

    StepVerifier.withVirtualTime(
            () -> {
              final NodeActivity activity = new NodeActivity();
              activity.touch();
              Schedulers.parallel()
                  .schedule(() -> paused.set(false), 119_500, TimeUnit.MILLISECONDS);
              final Flux<Message<?>> output = emitAfter(Duration.ofMillis(119_600), msg);
              return decorate(output, activity, paused::get, Duration.ofSeconds(1));
            })
        .expectSubscription()
        .thenAwait(Duration.ofMillis(119_600))
        .expectNext(msg)
        .verifyComplete();
  }

  @Test
  void cancelledBeforeCompletion_reportsCancelledOnce() {
    StepVerifier.create(decorate(Flux.never())).expectSubscription().thenCancel().verify();

    expectStatus(TaskStatus.RUNNING, 1);
    expectStatus(TaskStatus.CANCELLED, 1);
    expectStatus(TaskStatus.SUCCESS, 0);
  }

  @Test
  void completedStream_isNotReportedCancelledWhenTheSubscriberLaterCancels() {
    StepVerifier.create(decorate(Flux.just(DefaultMessage.create(UUID.randomUUID(), "x"))))
        .expectNextCount(1)
        .verifyComplete();

    expectStatus(TaskStatus.SUCCESS, 1);
    expectStatus(TaskStatus.CANCELLED, 0);
  }

  @Test
  void guard_activityKeepsTheNodeAlive_butSilenceTimesItOut() {
    StepVerifier.withVirtualTime(
            () -> {
              final NodeActivity activity = new NodeActivity();
              activity.touch();
              // emits at 0.5 s, then goes quiet
              final Flux<Message<?>> source =
                  emitAfter(Duration.ofMillis(500), DefaultMessage.create(UUID.randomUUID(), "x"))
                      .concatWith(Flux.never());
              return decorate(source, activity, () -> false, Duration.ofSeconds(1));
            })
        .expectSubscription()
        .thenAwait(Duration.ofMillis(500))
        .expectNextCount(1)
        .expectNoEvent(Duration.ofMillis(900))
        .thenAwait(Duration.ofMillis(200))
        .expectError(TimeoutException.class)
        .verify();
  }

  @Test
  void guard_pauseAtTheFirstObservationDoesNotCount() {
    final AtomicBoolean paused = new AtomicBoolean(true);

    StepVerifier.withVirtualTime(
            () -> {
              final NodeActivity activity = new NodeActivity();
              activity.touch();
              Schedulers.parallel().schedule(() -> paused.set(false), 3, TimeUnit.SECONDS);
              return decorate(Flux.never(), activity, paused::get, Duration.ofSeconds(1));
            })
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(3900))
        .thenAwait(Duration.ofMillis(200))
        .expectError(TimeoutException.class)
        .verify();
  }

  @Test
  void touch_isCheapEnoughForEveryMessage() {
    final NodeActivity activity = new NodeActivity();
    Duration best = Duration.ofDays(1);
    // best of three rounds, so one slow round on a busy build machine does not fail the test
    for (int round = 0; round < 3; round++) {
      final long started = System.nanoTime();
      for (int i = 0; i < 5_000_000; i++) {
        activity.touch();
      }
      final Duration took = Duration.ofNanos(System.nanoTime() - started);
      if (took.compareTo(best) < 0) {
        best = took;
      }
    }
    // about 2 us per touch before the clock became a volatile write: that would be 10 s
    assertThat(best).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void failureStatus_distinguishesUpstreamTimeoutAndOwnFailure() {
    final Throwable own = new IllegalStateException(BOOM);

    assertThat(NodeStreams.failureStatus(own)).isEqualTo(TaskStatus.FAILURE);
    assertThat(NodeStreams.failureStatus(new TimeoutException("idle")))
        .isEqualTo(TaskStatus.TIMED_OUT);
    assertThat(NodeStreams.failureStatus(UpstreamFailure.mark(own)))
        .isEqualTo(TaskStatus.UPSTREAM_FAILED);
  }

  @Test
  void upstreamFailure_keepsTheCauseAndIsNotWrappedTwice() {
    final Throwable own = new IllegalStateException(BOOM);

    final Throwable marked = UpstreamFailure.mark(own);

    assertThat(marked).hasCause(own).hasMessage(BOOM);
    assertThat(UpstreamFailure.mark(marked)).isSameAs(marked);
  }

  @Test
  void upstreamError_reportsUpstreamFailed() {
    StepVerifier.create(
            decorate(
                Flux.<Message<?>>error(UpstreamFailure.mark(new IllegalStateException(BOOM))),
                new NodeActivity(),
                () -> false,
                Duration.ofSeconds(1)))
        .expectError(UpstreamFailure.class)
        .verify();

    verify(tracker).emitTaskStatusEvent(EXEC, NODE_ID, TASK, TaskStatus.UPSTREAM_FAILED, Map.of());
  }

  @Test
  void ownFailure_carriesTheReasonOnItsStatusEvent() {
    StepVerifier.create(decorate(Flux.error(new IllegalStateException(BOOM))))
        .expectError(IllegalStateException.class)
        .verify();

    verify(tracker)
        .emitTaskStatusEvent(
            EXEC, NODE_ID, TASK, TaskStatus.FAILURE, Map.of(NodeStreams.ERROR_KEY, BOOM));
  }

  @Test
  void ownFailure_carriesTheRatedProblemLinesOfItsReason() {
    final String reason = "Process failed with exit code 1\n> Task :x\nwarning: careful";
    StepVerifier.create(decorate(Flux.error(new IllegalStateException(reason))))
        .expectError(IllegalStateException.class)
        .verify();

    verify(tracker)
        .emitTaskStatusEvent(
            EXEC,
            NODE_ID,
            TASK,
            TaskStatus.FAILURE,
            Map.of(
                NodeStreams.ERROR_KEY,
                reason,
                NodeStreams.PROBLEMS_KEY,
                List.of(
                    Map.of("level", "ERROR", "line", "Process failed with exit code 1"),
                    Map.of("level", "WARN", "line", "warning: careful"))));
  }

  @Test
  void describe_namesTheClassWhenThereIsNoMessage() {
    assertThat(NodeStreams.describe(new IllegalStateException()))
        .isEqualTo("IllegalStateException");
  }

  @Test
  void describe_namesTheClassWhenTheMessageIsBlank() {
    assertThat(NodeStreams.describe(new IllegalStateException(" ")))
        .isEqualTo("IllegalStateException");
  }

  @Test
  void describe_keepsHeadAndTailOfLongMessage() {
    final String text = "head" + "x".repeat(20_000) + "tail";

    final String described = NodeStreams.describe(new IllegalStateException(text));

    assertThat(described)
        .startsWith("head")
        .endsWith("tail")
        .contains("chars cut")
        .hasSizeLessThan(8_100);
  }

  @Test
  void silentNode_isReportedStalledOnce_andKeepsRunning() {
    final NodeActivity activity = new NodeActivity();

    StepVerifier.create(decorateWithStall(activity, Duration.ofMillis(100)))
        .expectSubscription()
        .then(activity::touch)
        .thenAwait(Duration.ofMillis(600))
        .then(() -> expectStalledWarnings(1))
        .thenCancel()
        .verify();

    expectStatus(TaskStatus.FAILURE, 0);
    expectStatus(TaskStatus.TIMED_OUT, 0);
  }

  @Test
  void activeNode_isNotReportedStalled() {
    final NodeActivity activity = new NodeActivity();

    StepVerifier.create(decorateWithStall(activity, Duration.ofMillis(400)))
        .expectSubscription()
        .then(activity::touch)
        .thenAwait(Duration.ofMillis(250))
        .then(activity::touch)
        .thenAwait(Duration.ofMillis(250))
        .then(() -> expectStalledWarnings(0))
        .thenCancel()
        .verify();
  }

  @Test
  void nodeThatNeverStarted_isNotReportedStalled() {
    StepVerifier.create(decorateWithStall(new NodeActivity(), Duration.ofMillis(100)))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(400))
        .then(() -> expectStalledWarnings(0))
        .thenCancel()
        .verify();
  }
}
