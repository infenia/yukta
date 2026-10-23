// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlActor;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** The open-stream cap and the stream lifetime of {@link SseStreams}. */
@NoArgsConstructor
class SseStreamLimitsTest {

  /** Heartbeat interval that never fires in these tests. */
  private static final Duration BEAT = Duration.ofSeconds(30);

  @AfterEach
  void clearLimits() {
    System.clearProperty(SseStreams.MAX_CONCURRENT_PROPERTY);
    System.clearProperty(SseStreams.MAX_LIFETIME_PROPERTY);
    System.clearProperty(SseStreams.MAX_PER_KEY_PROPERTY);
  }

  @Test
  void keyBeyondItsStreamCap_isAnswered429_butAnotherKeyIsNot() {
    System.setProperty(SseStreams.MAX_PER_KEY_PROPERTY, "1");
    final Sinks.Many<String> held = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(
            SseStreams.events(held.asFlux(), null, BEAT)
                .contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "key:alice")))
        .then(
            () -> {
              StepVerifier.create(
                      SseStreams.events(Flux.just("x"), null, BEAT)
                          .contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "key:alice")))
                  .expectError(ResponseStatusException.class)
                  .verify();
              StepVerifier.create(
                      SseStreams.events(Flux.just("x"), null, BEAT)
                          .contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "key:bob")))
                  .expectNextCount(1)
                  .verifyComplete();
            })
        .thenCancel()
        .verify();
  }

  @Test
  void streamBeyondTheCap_isAnswered429_andSlotIsFreeOnceOneCloses() {
    System.setProperty(SseStreams.MAX_CONCURRENT_PROPERTY, "1");
    final Sinks.Many<String> first = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(SseStreams.events(first.asFlux(), null, BEAT))
        .then(
            () ->
                StepVerifier.create(SseStreams.events(Flux.just("x"), null, BEAT))
                    .expectErrorSatisfies(
                        error ->
                            assertThat(error)
                                .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e ->
                                        assertThat(e.getStatusCode())
                                            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)))
                    .verify())
        .then(first::tryEmitComplete)
        .verifyComplete();

    StepVerifier.create(SseStreams.events(Flux.just("y"), null, BEAT))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void zeroTurnsTheCapOff() {
    System.setProperty(SseStreams.MAX_CONCURRENT_PROPERTY, "0");
    System.setProperty(SseStreams.MAX_PER_KEY_PROPERTY, "0");
    final Sinks.Many<String> held = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(SseStreams.events(held.asFlux(), null, BEAT))
        .then(
            () ->
                StepVerifier.create(SseStreams.events(Flux.just("x"), null, BEAT))
                    .expectNextCount(1)
                    .verifyComplete())
        .thenCancel()
        .verify();
  }

  @Test
  void streamIsClosedAfterItsLifetime() {
    System.setProperty(SseStreams.MAX_LIFETIME_PROPERTY, "200ms");

    StepVerifier.create(SseStreams.events(Flux.never(), null, BEAT))
        .expectComplete()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void zeroLifetimeKeepsTheStreamOpen() {
    System.setProperty(SseStreams.MAX_LIFETIME_PROPERTY, "0s");

    StepVerifier.create(SseStreams.events(Flux.never(), null, BEAT))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(300))
        .thenCancel()
        .verify();
  }
}
