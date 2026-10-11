// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Tests for AggregateProcessor. */
@NoArgsConstructor
class AggregateProcessorTest {

  /** Literal count. */
  private static final String COUNT = "count";

  /** Literal items. */
  private static final String ITEMS = "items";

  /** Literal size. */
  private static final String SIZE = "size";

  /** The processor under test. */
  private final AggregateProcessor processor = new AggregateProcessor();

  private static Message<?> msg(final int value) {
    return DefaultMessage.create(UUID.randomUUID(), value);
  }

  @Test
  void process_batchesBySizeAndFlushesRemainder() {
    StepVerifier.create(processor.process(Flux.just(msg(1), msg(2), msg(3)), Map.of(SIZE, 2)))
        .assertNext(m -> assertThat(m.payload()).isEqualTo(Map.of(ITEMS, List.of(1, 2), COUNT, 2)))
        .assertNext(m -> assertThat(m.payload()).isEqualTo(Map.of(ITEMS, List.of(3), COUNT, 1)))
        .verifyComplete();
  }

  @Test
  void process_timeoutFlushesPartialBatch() {
    final Sinks.Many<Message<?>> sink = Sinks.many().unicast().onBackpressureBuffer();
    StepVerifier.create(processor.process(sink.asFlux(), Map.of(SIZE, 10, "timeoutMs", 50)))
        .then(() -> sink.tryEmitNext(msg(1)))
        .assertNext(m -> assertThat(m.payload()).isEqualTo(Map.of(ITEMS, List.of(1), COUNT, 1)))
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void process_nonPositiveTimeoutFallsBackToSizeOnly() {
    StepVerifier.create(processor.process(Flux.just(msg(1)), Map.of(SIZE, 2, "timeoutMs", 0)))
        .assertNext(m -> assertThat(m.payload()).isEqualTo(Map.of(ITEMS, List.of(1), COUNT, 1)))
        .verifyComplete();
  }

  @Test
  void metadataAndValidation() {
    assertThat(processor.getType()).isEqualTo("AGGREGATE");
    assertThat(processor.describe().usagePattern()).contains(SIZE);
    StepVerifier.create(processor.validateConfig(Map.of(SIZE, 2))).verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of(SIZE, 0)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void describe_processorProvidesNodeDesign() {
    assertThat(processor.describe().uiDesign())
        .hasValueSatisfying(
            design -> {
              assertThat(design.html()).contains("{{nodeId}}");
              assertThat(design.width()).isEqualTo(140);
              assertThat(design.height()).isEqualTo(80);
            });
  }
}
