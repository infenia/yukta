// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class StartIdempotencyTest {

  private final MovableClock clock = new MovableClock();
  private final StartIdempotency idempotency = new StartIdempotency(Duration.ofMinutes(10), clock);
  private final AtomicInteger starts = new AtomicInteger();

  /** A clock the test can move. */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-10-10T00:00:00Z");

    /* default */ void advance(final Duration amount) {
      now = now.plus(amount);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private Mono<String> start() {
    return Mono.fromSupplier(() -> "exec-" + starts.incrementAndGet());
  }

  @Test
  void sameKey_returnsTheFirstExecution_andStartsOnlyOnce() {
    StepVerifier.create(idempotency.once("s/w", "k1", this::start))
        .expectNext("exec-1")
        .verifyComplete();
    StepVerifier.create(idempotency.once("s/w", "k1", this::start))
        .expectNext("exec-1")
        .verifyComplete();

    assertThat(starts).hasValue(1);
  }

  @Test
  void differentKeysAndScopes_startSeparately() {
    idempotency.once("s/w", "k1", this::start).block();
    idempotency.once("s/w", "k2", this::start).block();
    idempotency.once("s/other", "k1", this::start).block();

    assertThat(starts).hasValue(3);
  }

  @Test
  void noKey_neverDeduplicates() {
    idempotency.once("s/w", null, this::start).block();
    idempotency.once("s/w", "  ", this::start).block();
    idempotency.once("s/w", null, this::start).block();

    assertThat(starts).hasValue(3);
  }

  @Test
  void afterTheWindow_theKeyIsForgotten() {
    idempotency.once("s/w", "k1", this::start).block();

    clock.advance(Duration.ofMinutes(11));

    StepVerifier.create(idempotency.once("s/w", "k1", this::start))
        .expectNext("exec-2")
        .verifyComplete();
  }

  @Test
  void failedStart_isNotRemembered_soTheRetryRunsAgain() {
    final AtomicInteger calls = new AtomicInteger();

    StepVerifier.create(
            idempotency.once(
                "s/w",
                "k1",
                () ->
                    Mono.defer(
                        () ->
                            Mono.<String>error(
                                new IllegalStateException("x" + calls.incrementAndGet())))))
        .expectError(IllegalStateException.class)
        .verify();
    StepVerifier.create(idempotency.once("s/w", "k1", this::start))
        .expectNext("exec-1")
        .verifyComplete();

    assertThat(calls).hasValue(1);
  }

  @Test
  void defaultConstructor_usesTheSystemClock() {
    final StartIdempotency real = new StartIdempotency(Duration.ofMinutes(1));

    assertThat(real.once("s/w", "k", () -> Mono.just("a")).block()).isEqualTo("a");
    assertThat(real.once("s/w", "k", () -> Mono.just("b")).block()).isEqualTo("a");
  }
}
