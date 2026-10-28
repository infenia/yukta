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

    now.addAndGet(Duration.ofMinutes(1).toNanos());

    assertThat(limiter.tryAcquire(BOT)).isTrue();
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

    now.addAndGet(Duration.ofMinutes(1).toNanos());

    assertThat(limiter.exceeded(BOT)).isFalse();
    assertThat(new CallRateLimiter(0).exceeded(BOT)).isFalse();
  }

  @Test
  void testManyCallers_endedWindowsAreForgotten() {
    final CallRateLimiter limiter = new CallRateLimiter(1, now::get);
    for (int i = 0; i < 10_001; i++) {
      limiter.tryAcquire("caller-" + i);
    }
    now.addAndGet(Duration.ofMinutes(1).toNanos());

    assertThat(limiter.tryAcquire("caller-0")).isTrue();
    assertThat(limiter.exceeded("caller-1")).isFalse();
  }

  @Test
  void testZeroMeansUnlimited() {
    final CallRateLimiter limiter = new CallRateLimiter(0);

    for (int i = 0; i < 10_000; i++) {
      assertThat(limiter.tryAcquire(BOT)).isTrue();
    }
  }
}
