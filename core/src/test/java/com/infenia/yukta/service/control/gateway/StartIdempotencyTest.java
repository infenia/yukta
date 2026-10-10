// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class StartIdempotencyTest {

  private final AtomicLong nanos = new AtomicLong();
  private final StartIdempotency idempotency =
      new StartIdempotency(Duration.ofMinutes(10), nanos::get);
  private final AtomicInteger starts = new AtomicInteger();

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

    nanos.addAndGet(Duration.ofMinutes(11).toNanos());

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

  @Test
  void keys_surviveRestart_whenLogIsKept() {
    final com.infenia.yukta.service.log.InMemoryExecutionLogStore log =
        new com.infenia.yukta.service.log.InMemoryExecutionLogStore();
    final StartIdempotency before = new StartIdempotency(Duration.ofMinutes(10), log);
    before.afterSingletonsInstantiated();
    assertThat(before.once("s/w", "k1", this::start).block()).isEqualTo("exec-1");

    final StartIdempotency after = new StartIdempotency(Duration.ofMinutes(10), log);
    after.afterSingletonsInstantiated();

    assertThat(after.once("s/w", "k1", this::start).block()).isEqualTo("exec-1");
    assertThat(after.once("s/w", "k2", this::start).block()).isEqualTo("exec-2");
    assertThat(starts).hasValue(2);
  }

  @Test
  void expiredKeys_areNotReloaded_andFailedStartIsNotLogged() {
    final com.infenia.yukta.service.log.InMemoryExecutionLogStore log =
        new com.infenia.yukta.service.log.InMemoryExecutionLogStore();
    final StartIdempotency first = new StartIdempotency(Duration.ofMinutes(10), log);
    first
        .once("s/w", "failed", () -> Mono.<String>error(new IllegalStateException("no")))
        .onErrorResume(e -> Mono.empty())
        .block();
    first.once("s/w", "old", this::start).block();

    final StartIdempotency tiny = new StartIdempotency(Duration.ofNanos(1), log);
    tiny.afterSingletonsInstantiated();
    final StartIdempotency normal = new StartIdempotency(Duration.ofMinutes(10), log);
    normal.afterSingletonsInstantiated();

    assertThat(log.readAll()).hasSize(1);
    assertThat(tiny.once("s/w", "old", this::start).block()).isEqualTo("exec-2");
    assertThat(normal.once("s/w", "failed", this::start).block()).isEqualTo("exec-3");
  }

  @Test
  void withoutLog_reloadingDoesNothing() {
    final StartIdempotency idle = new StartIdempotency(Duration.ofMinutes(1));
    idle.afterSingletonsInstantiated();

    assertThat(idle.once("s/w", "k", this::start).block()).isEqualTo("exec-1");
  }
}
