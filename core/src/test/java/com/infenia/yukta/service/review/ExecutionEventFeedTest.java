// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
@NoArgsConstructor
class ExecutionEventFeedTest {

  private static final Function<String, ExecutionEvent> INPUT =
      id -> new ExecutionEvent.InputExpired(id, "s", "wf", "r-" + id, Instant.now(), "n");

  private static ExecutionEvent event(final String id) {
    return new ExecutionEvent.WorkflowStatusChanged(id, "s", "wf", "RUNNING", Instant.now());
  }

  @Test
  void events_everySubscriberGetsLaterEvents_andNotEarlierOnes() {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    feed.publish(event("before"));

    final var a = StepVerifier.create(feed.events().take(1)).expectNextCount(1).expectComplete();
    final var b = StepVerifier.create(feed.events().take(1)).expectNextCount(1).expectComplete();
    final var verifierA = a.verifyLater();
    final var verifierB = b.verifyLater();
    feed.publish(event("after"));

    verifierA.verify();
    verifierB.verify();
  }

  @Test
  void latencies_subscribersGetLaterTimingsOnly() {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final var timing = new EngineLatency("e", "s", "w", EngineLatency.Stage.HANDOFF, 3);
    feed.publishLatency(new EngineLatency("e", "s", "w", EngineLatency.Stage.START, 1));

    final var seen =
        StepVerifier.create(feed.latencies().take(1)).expectNext(timing).expectComplete();
    final var verifier = seen.verifyLater();
    feed.publishLatency(timing);

    verifier.verify();
  }

  @Test
  void latencies_overflow_dropsAndCountsLatency() {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    Metrics.addRegistry(meters);
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final var stuck =
        new reactor.core.publisher.BaseSubscriber<EngineLatency>() {
          @Override
          protected void hookOnSubscribe(final org.reactivestreams.Subscription subscription) {
            // never requests: the buffer fills
          }
        };
    feed.latencies().subscribe(stuck);
    try {
      IntStream.range(0, 10_000 + 500)
          .forEach(i ->
              feed.publishLatency(
                  new EngineLatency("e", "s", "w", EngineLatency.Stage.HANDOFF, i)));
      assertThat(meters.counter("yukta.tracker.dropped", "kind", "latency").count()).isPositive();
    } finally {
      stuck.dispose();
      Metrics.removeRegistry(meters);
    }
  }

  @Test
  void subscriberCount_countsTheReadersSubscribedNow() {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    assertThat(feed.subscriberCount()).isZero();

    final Disposable reader = feed.events().subscribe();
    assertThat(feed.subscriberCount()).isEqualTo(1);

    reader.dispose();
    assertThat(feed.subscriberCount()).isZero();
  }

  @Test
  void events_blockedSubscriber_doesNotBlockPublisher() throws InterruptedException {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final CountDownLatch release = new CountDownLatch(1);
    final Disposable subscription =
        feed.events()
            .subscribe(e -> {
              try {
                release.await();
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            });

    final CountDownLatch published = new CountDownLatch(1);
    final Thread publisher =
        new Thread(() -> {
          for (int i = 0; i < 1000; i++) {
            feed.publish(event("e" + i));
          }
          published.countDown();
        });
    publisher.start();

    final boolean finished = published.await(5, TimeUnit.SECONDS);
    release.countDown();
    subscription.dispose();
    assertThat(finished).isTrue();
  }

  @Test
  void events_overflow_dropsAndCounts() throws InterruptedException {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    Metrics.addRegistry(meters);
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final CountDownLatch release = new CountDownLatch(1);
    final Disposable subscription =
        feed.events()
            .subscribe(e -> {
              try {
                release.await();
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            });
    try {
      for (int i = 0; i < 11_000; i++) {
        feed.publish(event("e" + i));
      }
      assertThat(meters.counter("yukta.tracker.dropped", "kind", "feed").count()).isPositive();
    } finally {
      release.countDown();
      subscription.dispose();
      Metrics.removeRegistry(meters);
    }
  }

  @Test
  void publish_noSubscriber_isDropped() {
    new ExecutionEventFeed().publish(event("nobody"));
  }

  @Nested
  /* default */ final class Reliable {
    @Test
    void reliable_keepsInputEventsInOrderThroughBurstOfLogLines() {
      final ExecutionEventFeed feed = new ExecutionEventFeed();
      final java.util.List<ExecutionEvent> got = new java.util.concurrent.CopyOnWriteArrayList<>();
      final Disposable reader = feed.reliable().subscribe(got::add);

      feed.publish(INPUT.apply("a"));
      IntStream.range(0, 20_000)
          .forEach(i ->
              feed.publish(
                  new ExecutionEvent.LogLine(
                      "e", "s", "wf", "n", "line " + i, LogLevel.INFO, Instant.now())));
      feed.publish(INPUT.apply("b"));
      feed.publish(event("status"));

      org.awaitility.Awaitility.await().until(() -> got.size() == 3);
      assertThat(got).extracting(ExecutionEvent::executionId).containsExactly("a", "b", "status");
      reader.dispose();
    }

    @Test
    @ExpectedErrorLog("ERROR com.infenia.yukta.service.review.ExecutionEventFeed - ")
    void reliable_dropsOnlyWhenFullAndCountsIt() {
      final ExecutionEventFeed feed = new ExecutionEventFeed();
      final SimpleMeterRegistry registry = new SimpleMeterRegistry();
      Metrics.addRegistry(registry);
      final var stuck =
          new reactor.core.publisher.BaseSubscriber<ExecutionEvent>() {
            @Override
            protected void hookOnSubscribe(final org.reactivestreams.Subscription subscription) {
              // never requests: the queue fills
            }
          };
      feed.reliable().subscribe(stuck);
      try {
        for (int i = 0; i < 10_000 + 1_500; i++) {
          feed.publish(INPUT.apply("x" + i));
        }

        assertThat(registry.counter("yukta.notifications.tap.dropped").count()).isEqualTo(1_500);
      } finally {
        stuck.dispose();
        Metrics.removeRegistry(registry);
      }
    }

    @Test
    void reliable_isNotFedWhileNobodyReadsAndSecondReaderReplacesTheFirst() {
      final ExecutionEventFeed feed = new ExecutionEventFeed();
      feed.publish(INPUT.apply("lost"));

      final java.util.List<String> second = new java.util.concurrent.CopyOnWriteArrayList<>();
      final Disposable first = feed.reliable().subscribe(e -> {});
      final Disposable reader = feed.reliable().subscribe(e -> second.add(e.executionId()));
      first.dispose();
      feed.publish(INPUT.apply("later"));

      org.awaitility.Awaitility.await().until(() -> second.size() == 1);
      assertThat(second).containsExactly("later");
      reader.dispose();
      feed.publish(INPUT.apply("after"));
      assertThat(second).hasSize(1);
    }
  }
}
