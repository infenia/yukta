// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AutoKeyWindowTest {

  private final AtomicInteger starts = new AtomicInteger();

  private Mono<String> start() {
    return Mono.fromSupplier(() -> "run-" + starts.incrementAndGet());
  }

  @Test
  void repeatInsideTheWindowGetsTheFirstRun() {
    final AutoKeyWindow window = new AutoKeyWindow(Duration.ofMinutes(1));

    StepVerifier.create(window.once("s", this::start)).expectNext("run-1").verifyComplete();
    StepVerifier.create(window.once("s", this::start)).expectNext("run-1").verifyComplete();
    StepVerifier.create(window.once("other", this::start)).expectNext("run-2").verifyComplete();
    assertThat(starts).hasValue(2);
  }

  @Test
  void zeroWindowStartsEveryTime() {
    final AutoKeyWindow window = new AutoKeyWindow(Duration.ZERO);

    StepVerifier.create(window.once("s", this::start)).expectNext("run-1").verifyComplete();
    StepVerifier.create(window.once("s", this::start)).expectNext("run-2").verifyComplete();
  }

  @Test
  void failedStartIsNotRemembered() {
    final AutoKeyWindow window = new AutoKeyWindow(Duration.ofMinutes(1));

    StepVerifier.create(window.once("s", () -> Mono.error(new IllegalStateException("no"))))
        .expectError(IllegalStateException.class)
        .verify();
    StepVerifier.create(window.once("s", this::start)).expectNext("run-1").verifyComplete();
  }

  @Test
  void expiredEntryStartsAgain() throws InterruptedException {
    final AutoKeyWindow window = new AutoKeyWindow(Duration.ofMillis(20));

    StepVerifier.create(window.once("s", this::start)).expectNext("run-1").verifyComplete();
    Thread.sleep(40);
    StepVerifier.create(window.once("s", this::start)).expectNext("run-2").verifyComplete();
  }
}
