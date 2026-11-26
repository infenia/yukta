// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class PriorityEdgeBufferTest {

  private static final String HIGH = "high";
  private static final String LOW1 = "low1";
  private static final String LOW2 = "low2";

  private static Message<?> msg(final String name, final int priority) {
    return DefaultMessage.create(null, name).withPriority(priority);
  }

  private static Flux<Message<?>> buffered(
      final Flux<Message<?>> input,
      final int size,
      final boolean dropOldest,
      final List<Object> dropped) {
    return PriorityEdgeBuffer.buffered(input, size, dropOldest, m -> dropped.add(m.payload()));
  }

  private static void emit(final Sinks.Many<Message<?>> sink, final Message<?>... messages) {
    for (final Message<?> m : messages) {
      sink.tryEmitNext(m);
    }
    sink.tryEmitComplete();
  }

  @Test
  void handsOutTheHighestPriorityFirstAndTiesInArrivalOrder() {
    final Sinks.Many<Message<?>> in = Sinks.many().unicast().onBackpressureBuffer();
    final List<Object> dropped = new ArrayList<>();

    StepVerifier.create(buffered(in.asFlux(), 10, true, dropped).<Object>map(Message::payload), 0)
        .then(() -> emit(in, msg("a", 0), msg("b", 5), msg("c", 0), msg("d", 9), msg("e", 5)))
        .thenRequest(Long.MAX_VALUE)
        .expectNext("d", "b", "e", "a", "c")
        .verifyComplete();
    assertThat(dropped).isEmpty();
  }

  @Test
  void whenFullDropsTheOldestOrNewestOfTheLowestPriority() {
    final Sinks.Many<Message<?>> oldest = Sinks.many().unicast().onBackpressureBuffer();
    final Sinks.Many<Message<?>> newest = Sinks.many().unicast().onBackpressureBuffer();
    final List<Object> droppedOldest = new ArrayList<>();
    final List<Object> droppedNewest = new ArrayList<>();

    StepVerifier.create(
            buffered(oldest.asFlux(), 2, true, droppedOldest).<Object>map(Message::payload), 0)
        .then(() -> emit(oldest, msg(LOW1, 0), msg(HIGH, 9), msg(LOW2, 0)))
        .thenRequest(Long.MAX_VALUE)
        .expectNext(HIGH, LOW2)
        .verifyComplete();
    StepVerifier.create(
            buffered(newest.asFlux(), 2, false, droppedNewest).<Object>map(Message::payload), 0)
        .then(() -> emit(newest, msg(LOW1, 0), msg(HIGH, 9), msg(LOW2, 0)))
        .thenRequest(Long.MAX_VALUE)
        .expectNext(HIGH, LOW1)
        .verifyComplete();

    assertThat(droppedOldest).containsExactly(LOW1);
    assertThat(droppedNewest).containsExactly(LOW2);
  }

  @Test
  void failureReachesTheNodeAfterWhatWasWaiting() {
    final Sinks.Many<Message<?>> in = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(
            buffered(in.asFlux(), 5, true, new ArrayList<>()).<Object>map(Message::payload), 0)
        .then(() -> {
          in.tryEmitNext(msg("a", 1));
          in.tryEmitError(new IllegalStateException("boom"));
        })
        .thenRequest(1)
        .expectNext("a")
        .verifyErrorMessage("boom");
  }

  @Test
  void cancelReachesTheInputAndTheContextReachesIt() {
    final AtomicBoolean cancelled = new AtomicBoolean();
    final Flux<Message<?>> input =
        Mono.deferContextual(ctx -> Mono.<Message<?>>just(msg(ctx.get("k"), 0)))
            .concatWith(Flux.never())
            .doOnCancel(() -> cancelled.set(true));

    StepVerifier.create(
            buffered(input, 5, true, new ArrayList<>())
                .<Object>map(Message::payload)
                .contextWrite(c -> c.put("k", "v")))
        .expectNext("v")
        .thenCancel()
        .verify(Duration.ofSeconds(5));
    assertThat(cancelled).isTrue();
  }

  @Test
  void edgeBufferedUsesItForPriorityOrderAndCountsDrops() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class);
    final Sinks.Many<Message<?>> in = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(
            NodePolicies.edgeBuffered(
                    new NodePolicy.EdgeBuffer(
                        1, NodePolicy.Overflow.DROP_NEWEST, NodePolicy.Order.PRIORITY),
                    in.asFlux(),
                    tracker,
                    "T",
                    "n1")
                .<Object>map(Message::payload),
            0)
        .then(() -> emit(in, msg("low", 0), msg(HIGH, 9)))
        .thenRequest(Long.MAX_VALUE)
        .expectNext(HIGH)
        .verifyComplete();
    verify(tracker, times(1)).recordEdgeDropped("T", "n1");
  }

  @Test
  void edgeBufferedWithPriorityAndDropOldestDropsTheOldestLowest() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class);
    final Sinks.Many<Message<?>> in = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(
            NodePolicies.edgeBuffered(
                    new NodePolicy.EdgeBuffer(
                        2, NodePolicy.Overflow.DROP_OLDEST, NodePolicy.Order.PRIORITY),
                    in.asFlux(),
                    tracker,
                    "T",
                    "n1")
                .<Object>map(Message::payload),
            0)
        .then(() -> emit(in, msg(LOW1, 0), msg(HIGH, 9), msg(LOW2, 0)))
        .thenRequest(Long.MAX_VALUE)
        .expectNext(HIGH, LOW2)
        .verifyComplete();
    verify(tracker, times(1)).recordEdgeDropped("T", "n1");
  }

  @Test
  void requestMadeWhileAnItemIsDeliveredIsServedByTheSameDrain() {
    final Sinks.Many<Message<?>> in = Sinks.many().unicast().onBackpressureBuffer();
    final List<Object> got = new ArrayList<>();

    buffered(in.asFlux(), 10, true, new ArrayList<>())
        .subscribe(
            new BaseSubscriber<>() {
              @Override
              protected void hookOnSubscribe(final Subscription subscription) {
                request(1);
              }

              @Override
              protected void hookOnNext(final Message<?> value) {
                got.add(value.payload());
                request(1);
              }
            });
    emit(in, msg("a", 0), msg("b", 0), msg("c", 0));

    assertThat(got).containsExactly("a", "b", "c");
  }
}
