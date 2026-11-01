// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link CallRateLimiter}. */
@NoArgsConstructor
class CallRateLimiterTest {

  /** A caller. */
  private static final String BOT = "bot";

  /** Fake clock in nanoseconds. */
  private final AtomicLong now = new AtomicLong();

  @Test
  void testCallsOverTheLimitAreRefusedUntilTheNextMinute() {
    final CallRateLimiter limiter = new CallRateLimiter(2, now::get);

    assertThat(limiter.tryAcquire(BOT)).isTrue();
    assertThat(limiter.tryAcquire(BOT)).isTrue();
    assertThat(limiter.tryAcquire(BOT)).isFalse();

    now.addAndGet(Duration.ofMinutes(2).toNanos());

    assertThat(limiter.tryAcquire(BOT)).isTrue();
  }

  @Test
  void testNoDoubleBudgetAcrossMinuteBoundary() {
    final CallRateLimiter limiter = new CallRateLimiter(2, now::get);
    limiter.tryAcquire(BOT);
    limiter.tryAcquire(BOT);

    now.addAndGet(Duration.ofSeconds(61).toNanos());

    // a fixed window would allow two more here; the sliding minute still holds the earlier two
    assertThat(limiter.tryAcquire(BOT)).isFalse();
  }

  @Test
  void testEachCallerHasItsOwnBudget() {
    final CallRateLimiter limiter = new CallRateLimiter(1, now::get);

    assertThat(limiter.tryAcquire("a")).isTrue();
    assertThat(limiter.tryAcquire("b")).isTrue();
    assertThat(limiter.tryAcquire("a")).isFalse();
  }

  @Test
  void testExceededLooksWithoutCounting_andEndsWithTheWindow() {
    final CallRateLimiter limiter = new CallRateLimiter(2, now::get);

    assertThat(limiter.exceeded(BOT)).isFalse();
    limiter.tryAcquire(BOT);
    assertThat(limiter.exceeded(BOT)).isFalse();
    limiter.tryAcquire(BOT);
    assertThat(limiter.exceeded(BOT)).isTrue();
    assertThat(limiter.exceeded(BOT)).isTrue();

    now.addAndGet(Duration.ofMinutes(2).toNanos());

    assertThat(limiter.exceeded(BOT)).isFalse();
    assertThat(new CallRateLimiter(0).exceeded(BOT)).isFalse();
  }

  @Test
  void testZeroMeansUnlimited() {
    final CallRateLimiter limiter = new CallRateLimiter(0);

    for (int i = 0; i < 10_000; i++) {
      assertThat(limiter.tryAcquire(BOT)).isTrue();
    }
  }
}
