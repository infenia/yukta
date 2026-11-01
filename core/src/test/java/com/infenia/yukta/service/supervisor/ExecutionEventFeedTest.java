// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
@NoArgsConstructor
class ExecutionEventFeedTest {

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
  void events_blockedSubscriber_doesNotBlockPublisher() throws InterruptedException {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final CountDownLatch release = new CountDownLatch(1);
    final Disposable subscription =
        feed.events()
            .subscribe(
                e -> {
                  try {
                    release.await();
                  } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                  }
                });

    final CountDownLatch published = new CountDownLatch(1);
    final Thread publisher =
        new Thread(
            () -> {
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
            .subscribe(
                e -> {
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
}
