// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The writer's shutdown: nothing waits for a signal that never comes. */
@NoArgsConstructor
class SerialWriterTest {

  @Test
  void disposeFailsAWriteThatIsStillPending() {
    final SerialWriter writer = new SerialWriter();

    StepVerifier.create(writer.submit(Mono::never))
        .then(writer::dispose)
        .expectError(IllegalStateException.class)
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void aWriteSubmittedAfterDisposeFails() {
    final SerialWriter writer = new SerialWriter();
    writer.dispose();

    StepVerifier.create(writer.submit(() -> Mono.just("late")))
        .expectError(IllegalStateException.class)
        .verify(Duration.ofSeconds(5));
  }
}
