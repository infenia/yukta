// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** How many model calls a decision node starts at once. */
@NoArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
class DecisionConcurrencyTest {

  private static Map<String, Object> config(final Object... extra) {
    final Map<String, Object> config = new HashMap<>();
    config.put("model", "nimble");
    config.put("question", "Is it fine?");
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  /** Runs {@code count} messages through a GATE and returns the most model calls seen at once. */
  private static int peakConcurrency(final Map<String, Object> config, final int count) {
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    final FakeModel model =
        new FakeModel(
            _ ->
                Mono.defer(
                    () -> {
                      peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                      return Mono.just(Map.<String, Answer>of("gate", new Answer.YesNo(1)))
                          .delayElement(Duration.ofMillis(20))
                          .doOnTerminate(inFlight::decrementAndGet);
                    }));
    final GatePlugin plugin = new GatePlugin(model, FakeModel.plainResolver());
    StepVerifier.create(
            plugin.process(Flux.range(0, count).map(i -> FakeModel.message("p")), config))
        .expectNextCount(count)
        .verifyComplete();
    return peak.get();
  }

  @Test
  void handlesAtMostTheConfiguredNumberOfModelCallsAtOnce() {
    assertThat(peakConcurrency(config("concurrency", 3), 20)).isEqualTo(3);
  }

  @Test
  void withoutConcurrencySixteenModelCallsAreInFlightAtMost() {
    assertThat(peakConcurrency(config(), 60)).isEqualTo(16);
  }

  @Test
  void validateConfigRejectsNonPositiveConcurrency() {
    final GatePlugin plugin = new GatePlugin(FakeModel.always(Map.of()), FakeModel.plainResolver());

    StepVerifier.create(plugin.validateConfig(config("concurrency", 0)))
        .expectErrorMessage("concurrency must be a positive whole number, got: 0")
        .verify();
  }
}
