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
@SuppressWarnings("PMD.TooManyMethods")
class AggregateProcessorTest {

  /** Literal count. */
  private static final String COUNT = "count";

  /** Literal items. */
  private static final String ITEMS = "items";

  /** Literal sources. */
  private static final String SOURCES = "sources";

  /** Literal size. */
  private static final String SIZE = "size";

  /** The processor under test. */
  private final AggregateProcessor processor = new AggregateProcessor();

  /** Literal timeoutMs. */
  private static final String TIMEOUT_MS = "timeoutMs";

  /** Literal by. */
  private static final String BY = "by";

  /** Literal sequence. */
  private static final String SEQUENCE = "sequence";

  /** Literal correlation. */
  private static final String CORRELATION = "correlation";

  /** Literal complete. */
  private static final String COMPLETE = "complete";

  private static Message<?> msg(final int value) {
    return DefaultMessage.create(UUID.randomUUID(), value);
  }

  /** A child the way SPLITTER makes it: the parent's id as sequence and a parentId header. */
  private static Message<?> part(final String parent, final int number, final int size) {
    return DefaultMessage.create(null, parent + number).toBuilder()
        .sequenceId(parent)
        .sequenceNumber(number)
        .sequenceSize(size)
        .correlationId(parent)
        .metadata(Map.of("k", "v", SplitterProcessor.PARENT_ID, parent))
        .build();
  }

  private static Map<?, ?> body(final Message<?> m) {
    return (Map<?, ?>) m.payload();
  }

  @Test
  void process_batchesBySizeAndFlushesRemainder() {
    StepVerifier.create(processor.process(Flux.just(msg(1), msg(2), msg(3)), Map.of(SIZE, 2)))
        .assertNext(m ->
            assertThat(m.payload())
                .isEqualTo(Map.of(ITEMS, List.of(1, 2), COUNT, 2, SOURCES, List.of("", ""))))
        .assertNext(m ->
            assertThat(m.payload())
                .isEqualTo(Map.of(ITEMS, List.of(3), COUNT, 1, SOURCES, List.of(""))))
        .verifyComplete();
  }

  @Test
  void process_namesTheNodeEachItemCameFrom() {
    StepVerifier.create(
            processor.process(
                Flux.just(msg(1).withSourceNodeId("style"), msg(2).withSourceNodeId("spotbugs")),
                Map.of(SIZE, 2)))
        .assertNext(m ->
            assertThat(((Map<?, ?>) m.payload()).get(SOURCES))
                .isEqualTo(List.of("style", "spotbugs")))
        .verifyComplete();
  }

  @Test
  void process_recordsSourceIds() {
    final Message<?> first = msg(1);
    final Message<?> second = msg(2);
    StepVerifier.create(processor.process(Flux.just(first, second), Map.of(SIZE, 2)))
        .assertNext(m ->
            assertThat(m.metadata().get("sourceIds")).isEqualTo(List.of(first.id(), second.id())))
        .verifyComplete();
  }

  @Test
  void process_timeoutFlushesPartialBatch() {
    final Sinks.Many<Message<?>> sink = Sinks.many().unicast().onBackpressureBuffer();
    StepVerifier.create(processor.process(sink.asFlux(), Map.of(SIZE, 10, TIMEOUT_MS, 50)))
        .then(() -> sink.tryEmitNext(msg(1)))
        .assertNext(m ->
            assertThat(m.payload())
                .isEqualTo(Map.of(ITEMS, List.of(1), COUNT, 1, SOURCES, List.of(""))))
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void process_nonPositiveTimeoutFallsBackToSizeOnly() {
    StepVerifier.create(processor.process(Flux.just(msg(1)), Map.of(SIZE, 2, TIMEOUT_MS, 0)))
        .assertNext(m ->
            assertThat(m.payload())
                .isEqualTo(Map.of(ITEMS, List.of(1), COUNT, 1, SOURCES, List.of(""))))
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
        .hasValueSatisfying(design -> {
          assertThat(design.label()).isNotBlank();
          assertThat(design.width()).isEqualTo(140);
          assertThat(design.height()).isEqualTo(80);
        });
  }

  @Test
  void process_zeroSizeIsRejected() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> processor.process(Flux.just(msg(1)), Map.of(SIZE, 0)).blockFirst());
  }

  @Test
  void process_missingOrNonNumericSizeIsRejected() {
    for (final Object size : new Object[] {"2", ""}) {
      final Map<String, Object> config = Map.of(SIZE, size);
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> processor.process(Flux.just(msg(1)), config).blockFirst());
    }
  }

  @Test
  void bySequence_undoesSplitWhateverTheOrderAndInterleaving() {
    final Flux<Message<?>> parts =
        Flux.just(
            part("p", 3, 3), part("q", 2, 2), part("p", 1, 3), part("q", 1, 2), part("p", 2, 3));

    StepVerifier.create(processor.process(parts, Map.of(BY, SEQUENCE)))
        .assertNext(m -> {
          assertThat(m.id()).isEqualTo("q");
          assertThat(m.correlationId()).isEqualTo("q");
          assertThat(body(m).get(ITEMS)).isEqualTo(List.of("q1", "q2"));
          assertThat(body(m).get(COMPLETE)).isEqualTo(true);
          assertThat(body(m).get("missing")).isEqualTo(List.of());
          assertThat(m.sequenceId()).isNull();
          assertThat(m.sequenceSize()).isZero();
          assertThat(m.metadata())
              .containsEntry("k", "v")
              .doesNotContainKey(SplitterProcessor.PARENT_ID);
          assertThat(m.sourcePort()).isEqualTo("default");
        })
        .assertNext(m -> {
          assertThat(m.id()).isEqualTo("p");
          assertThat(body(m).get(ITEMS)).isEqualTo(List.of("p1", "p2", "p3"));
        })
        .verifyComplete();
  }

  @Test
  void bySequence_joinsWhatTheRealSplitterSplit() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, List.of("a", "b", "c")));
    final Flux<Message<?>> split =
        new SplitterProcessor().process(Flux.just(parent), Map.of("itemsPath", "payload.items"));

    StepVerifier.create(processor.process(split, Map.of(BY, SEQUENCE)))
        .assertNext(m -> {
          assertThat(m.id()).isEqualTo(parent.id());
          assertThat(m.traceId()).isEqualTo(parent.traceId());
          assertThat(body(m).get(ITEMS)).isEqualTo(List.of("a", "b", "c"));
        })
        .verifyComplete();
  }

  @Test
  void bySequence_flushesAnIncompleteGroupWithWhatIsMissing() {
    final Sinks.Many<Message<?>> sink = Sinks.many().unicast().onBackpressureBuffer();
    StepVerifier.create(processor.process(sink.asFlux(), Map.of(BY, SEQUENCE, TIMEOUT_MS, 50)))
        .then(() -> {
          sink.tryEmitNext(part("p", 1, 3));
          sink.tryEmitNext(part("p", 3, 3));
        })
        .assertNext(m -> {
          assertThat(body(m).get(COMPLETE)).isEqualTo(false);
          assertThat(body(m).get("missing")).isEqualTo(List.of(2));
          assertThat(body(m).get(ITEMS)).isEqualTo(List.of("p1", "p3"));
        })
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void bySequence_passesMessageWithoutSequenceOnAsGroupOfOne() {
    StepVerifier.create(processor.process(Flux.just(msg(7)), Map.of(BY, SEQUENCE)))
        .assertNext(m -> {
          assertThat(body(m).get(ITEMS)).isEqualTo(List.of(7));
          assertThat(body(m).get(COMPLETE)).isEqualTo(true);
        })
        .verifyComplete();
  }

  @Test
  void byCorrelation_joinsSizeMessagesThatShareAnId() {
    final Flux<Message<?>> input =
        Flux.just(
            msg(1).withCorrelationId("a"),
            msg(2).withCorrelationId("b"),
            msg(3).withCorrelationId("a"),
            msg(4).withCorrelationId("c"));

    StepVerifier.create(processor.process(input, Map.of(BY, CORRELATION, SIZE, 2)))
        .assertNext(m -> {
          assertThat(m.correlationId()).isEqualTo("a");
          assertThat(body(m).get(ITEMS)).isEqualTo(List.of(1, 3));
          assertThat(body(m).get(COMPLETE)).isEqualTo(true);
          assertThat(body(m).containsKey("missing")).isFalse();
        })
        // the open groups flush when the stream ends, incomplete
        .assertNext(m -> assertThat(body(m).get(COMPLETE)).isEqualTo(false))
        .assertNext(m -> assertThat(body(m).get(COMPLETE)).isEqualTo(false))
        .verifyComplete();
  }

  @Test
  void byValidation_sequenceNeedsNoSizeTheOthersDo() {
    StepVerifier.create(processor.validateConfig(Map.of(BY, SEQUENCE))).verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of(BY, CORRELATION)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of(BY, "nope")))
        .expectErrorMessage("by must be one of count, sequence, correlation; was 'nope'")
        .verify();
  }
}
