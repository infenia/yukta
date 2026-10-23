// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class CallRateLimiterTest {

  private final AtomicLong now = new AtomicLong();

  @Test
  void testCallsOverTheLimitAreRefusedUntilTheNextMinute() {
    final CallRateLimiter limiter = new CallRateLimiter(2, now::get);

    assertThat(limiter.tryAcquire("bot")).isTrue();
    assertThat(limiter.tryAcquire("bot")).isTrue();
    assertThat(limiter.tryAcquire("bot")).isFalse();

    now.addAndGet(Duration.ofMinutes(1).toNanos());

    assertThat(limiter.tryAcquire("bot")).isTrue();
  }

  @Test
  void testEachCallerHasItsOwnBudget() {
    final CallRateLimiter limiter = new CallRateLimiter(1, now::get);

    assertThat(limiter.tryAcquire("a")).isTrue();
    assertThat(limiter.tryAcquire("b")).isTrue();
    assertThat(limiter.tryAcquire("a")).isFalse();
  }

  @Test
  void testZeroMeansUnlimited() {
    final CallRateLimiter limiter = new CallRateLimiter(0);

    for (int i = 0; i < 10_000; i++) {
      assertThat(limiter.tryAcquire("bot")).isTrue();
    }
  }
}
