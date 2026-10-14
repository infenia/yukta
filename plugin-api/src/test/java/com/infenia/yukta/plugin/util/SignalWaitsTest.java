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
    assertThat(answer(waits, null)).isEqualTo(SignalWaits.NOT_WAITING);
  }

  @Test
  void await_timesOut_andForgetsTheWait() {
    final SignalWaits waits = new SignalWaits();

    StepVerifier.create(waits.await("e", "n", "ok", Duration.ofMillis(50)))
        .expectError(java.util.concurrent.TimeoutException.class)
        .verify();
    assertThat(answer(waits, "x")).isEqualTo(SignalWaits.NOT_WAITING);
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
}
