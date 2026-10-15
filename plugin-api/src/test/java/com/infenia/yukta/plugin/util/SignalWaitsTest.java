// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link SignalWaits}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SignalWaitsTest {

  private static com.infenia.yukta.message.Message<?> signal(final Object data) {
    final Map<String, Object> body = new java.util.HashMap<>();
    body.put("executionId", "e");
    body.put("nodeId", "n");
    body.put("name", "ok");
    body.put("decision", "approve");
    body.put("data", data);
    return DefaultMessage.create(UUID.randomUUID(), body);
  }

  private static String answer(final SignalWaits waits, final Object data) {
    return String.valueOf(
        java.util.Objects.requireNonNull(waits.deliver(signal(data)).block()).payload());
  }

  @Test
  void await_getsTheDecision_andLaterSignalFindsNoWait() {
    final SignalWaits waits = new SignalWaits();

    StepVerifier.create(waits.await("e", "n", "ok", Duration.ofSeconds(5)))
        .then(() -> assertThat(answer(waits, "fine")).isEqualTo(SignalWaits.ACCEPTED))
        .assertNext(d -> assertThat(d).isEqualTo(new SignalWaits.Decision("approve", "fine")))
        .verifyComplete();
    assertThat(answer(waits, null)).isEqualTo(SignalWaits.NOT_WAITING); // double submit
  }

  @Test
  void duplicateAfterAccept_isDropped_butLaterSignalIsKept() {
    final Hand clock = new Hand();
    final SignalWaits waits = new SignalWaits(clock);

    StepVerifier.create(waits.await("e", "n", "ok", Duration.ofSeconds(5)))
        .then(() -> answer(waits, "one"))
        .expectNextCount(1)
        .verifyComplete();
    assertThat(answer(waits, "dup")).isEqualTo(SignalWaits.NOT_WAITING);
    clock.advance(SignalWaits.DUPLICATE_WINDOW.plusSeconds(1));
    assertThat(answer(waits, "early")).isEqualTo(SignalWaits.QUEUED);
  }

  @Test
  void await_timesOut_andForgetsTheWait() {
    final SignalWaits waits = new SignalWaits();

    StepVerifier.create(waits.await("e", "n", "ok", Duration.ofMillis(50)))
        .expectError(java.util.concurrent.TimeoutException.class)
        .verify();
    assertThat(answer(waits, "x")).isEqualTo(SignalWaits.QUEUED);
  }

  @Test
  void deliver_ignoresPayloadsThatAreNotMaps() {
    StepVerifier.create(new SignalWaits().deliver(DefaultMessage.create(UUID.randomUUID(), "text")))
        .verifyComplete();
  }

  @Test
  void await_twoWaitsOnOneKey_areAnsweredOneSignalEachOldestFirst() {
    final SignalWaits waits = new SignalWaits();
    final var first = waits.await("e", "n", "ok", Duration.ofSeconds(5)).toFuture();
    final var second = waits.await("e", "n", "ok", Duration.ofSeconds(5)).toFuture();

    assertThat(answer(waits, "one")).isEqualTo(SignalWaits.ACCEPTED);
    assertThat(first).isCompleted();
    assertThat(second).isNotDone();
    assertThat(answer(waits, "two")).isEqualTo(SignalWaits.ACCEPTED);
    assertThat(second).isCompleted();
    assertThat(answer(waits, "three")).isEqualTo(SignalWaits.NOT_WAITING);
  }

  @Test
  void await_middleWaitTimesOut_othersStillGetTheirSignals() {
    final SignalWaits waits = new SignalWaits();
    final var first = waits.await("e", "n", "ok", Duration.ofSeconds(5)).toFuture();
    final var middle = waits.await("e", "n", "ok", Duration.ofMillis(50)).toFuture();
    final var third = waits.await("e", "n", "ok", Duration.ofSeconds(5)).toFuture();

    assertThat(middle).failsWithin(Duration.ofSeconds(2));
    assertThat(answer(waits, "one")).isEqualTo(SignalWaits.ACCEPTED);
    assertThat(answer(waits, "two")).isEqualTo(SignalWaits.ACCEPTED);
    assertThat(first).isCompleted();
    assertThat(third).isCompleted();
    assertThat(answer(waits, "three")).isEqualTo(SignalWaits.NOT_WAITING);
  }

  @Test
  void signalBeforeTheWait_isKept_andTheWaitTakesItAtOnce() {
    final SignalWaits waits = new SignalWaits();

    assertThat(answer(waits, "early")).isEqualTo(SignalWaits.QUEUED);

    StepVerifier.create(waits.await("e", "n", "ok", Duration.ofSeconds(5)))
        .assertNext(d -> assertThat(d).isEqualTo(new SignalWaits.Decision("approve", "early")))
        .verifyComplete();
    assertThat(waits.pending()).isEmpty();
  }

  @Test
  void earlySignals_areCapped() {
    final SignalWaits waits = new SignalWaits();
    for (int i = 0; i < SignalWaits.EARLY_CAP; i++) {
      final Map<String, Object> body = new java.util.HashMap<>();
      body.put("executionId", "e" + i);
      body.put("nodeId", "n");
      body.put("name", "ok");
      body.put("decision", "approve");
      waits.deliver(DefaultMessage.create(UUID.randomUUID(), body)).block();
    }

    assertThat(answer(waits, "one too many")).isEqualTo(SignalWaits.NOT_WAITING);
  }

  @Test
  void pending_listsOpenWaitsOldestFirst_andDropsThemWhenAnswered() {
    final SignalWaits waits = new SignalWaits();
    final var first = waits.await("e", "n", "ok", Duration.ofSeconds(5)).subscribe();
    final var second = waits.await("e2", "n2", "go", Duration.ofSeconds(5)).subscribe();

    assertThat(waits.pending())
        .extracting(SignalWaits.Pending::executionId, SignalWaits.Pending::name)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple("e", "ok"),
            org.assertj.core.api.Assertions.tuple("e2", "go"));
    answer(waits, "x");
    assertThat(waits.pending()).extracting(SignalWaits.Pending::executionId).containsExactly("e2");
    first.dispose();
    second.dispose();
    assertThat(waits.pending()).isEmpty();
  }

  @Test
  void shared_isOneInstance() {
    assertThat(SignalWaits.shared()).isSameAs(SignalWaits.shared());
  }

  /** A clock the test moves by hand. */
  private static final class Hand extends java.time.Clock {
    private java.time.Instant now = java.time.Instant.EPOCH;

    void advance(final Duration by) {
      now = now.plus(by);
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public java.time.Instant instant() {
      return now;
    }
  }

  @Test
  void earlySignal_isForgottenAfterItsTimeToLive() {
    final Hand clock = new Hand();
    final SignalWaits waits = new SignalWaits(clock);
    assertThat(answer(waits, "stale")).isEqualTo(SignalWaits.QUEUED);

    clock.advance(SignalWaits.EARLY_TTL.plusSeconds(1));

    // the stale one is not taken by a wait that begins now
    final var wait = waits.await("e", "n", "ok", Duration.ofMillis(100));
    StepVerifier.create(wait).expectError(java.util.concurrent.TimeoutException.class).verify();
  }

  @Test
  void staleEarlySignals_makeRoomForNewOnes_andNewSignalOnTheSameKeyReplacesTheOld() {
    final Hand clock = new Hand();
    final SignalWaits waits = new SignalWaits(clock);
    for (int i = 0; i < SignalWaits.EARLY_CAP; i++) {
      final Map<String, Object> body = new java.util.HashMap<>();
      body.put("executionId", "e" + i);
      body.put("nodeId", "n");
      body.put("name", "ok");
      body.put("decision", "approve");
      waits.deliver(DefaultMessage.create(UUID.randomUUID(), body)).block();
    }
    // at the cap, a key that already has a place keeps it and takes the newer decision
    final Map<String, Object> again = new java.util.HashMap<>();
    again.put("executionId", "e0");
    again.put("nodeId", "n");
    again.put("name", "ok");
    again.put("decision", "reject");
    assertThat(
            String.valueOf(
                java.util.Objects.requireNonNull(
                        waits.deliver(DefaultMessage.create(UUID.randomUUID(), again)).block())
                    .payload()))
        .isEqualTo(SignalWaits.QUEUED);
    StepVerifier.create(waits.await("e0", "n", "ok", Duration.ofSeconds(1)))
        .assertNext(d -> assertThat(d.decision()).isEqualTo("reject"))
        .verifyComplete();

    clock.advance(SignalWaits.EARLY_TTL.plusSeconds(1));
    final Map<String, Object> other = new java.util.HashMap<>();
    other.put("executionId", "fresh");
    other.put("nodeId", "n");
    other.put("name", "ok");
    other.put("decision", "approve");
    assertThat(
            String.valueOf(
                java.util.Objects.requireNonNull(
                        waits.deliver(DefaultMessage.create(UUID.randomUUID(), other)).block())
                    .payload()))
        .isEqualTo(SignalWaits.QUEUED);
  }
}
