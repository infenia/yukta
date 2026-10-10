// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
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
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyStaticImports", "PMD.TooManyMethods"})
class NodeStreamsTest {

  private static final String EXEC = "exec-1";
  private static final String NODE_ID = "node-1";
  private static final String TASK = "default";

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
        node, timeout, tracker, EXEC, source, activity, paused);
  }

  private void expectStatus(final TaskStatus status, final int times) {
    verify(tracker, times(times))
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(status), anyMap());
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
    StepVerifier.create(decorate(Flux.error(new IllegalStateException("boom"))))
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
    StepVerifier.create(decorate(Flux.empty())).verifyComplete();

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
    final long started = System.nanoTime();
    for (int i = 0; i < 5_000_000; i++) {
      activity.touch();
    }
    // about 2 us per touch before the clock became a volatile write: that would be 10 s
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }
}
