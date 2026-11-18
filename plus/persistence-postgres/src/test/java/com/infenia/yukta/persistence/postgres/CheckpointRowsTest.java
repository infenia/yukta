// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CheckpointRowsTest {

  @Test
  void await_returnsTheValueOfTheMono() {
    assertThat(CheckpointRows.await(Mono.just("value"), "fallback")).isEqualTo("value");
  }

  @Test
  void awaitRethrowsFailedMonoToCaller() {
    assertThatThrownBy(
            () -> CheckpointRows.await(Mono.error(new IllegalStateException("database down")), "x"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(java.util.concurrent.ExecutionException.class);
  }

  @Test
  void await_anInterruptedCallerGetsTheFallbackAndKeepsTheInterrupt() {
    Thread.currentThread().interrupt();
    try {
      assertThat(CheckpointRows.await(Mono.never(), "fallback")).isEqualTo("fallback");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
