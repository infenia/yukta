// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for KvStoreProcessor. */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
@NoArgsConstructor
class KvStoreProcessorTest {

  /** Test literal incr. */
  private static final String INCR = "incr";

  /** Test literal key. */
  private static final String KEY = "key";

  /** Test literal value. */
  private static final String VALUE = "value";

  /** Test literal put. */
  private static final String PUT = "put";

  /** Literal get. */
  private static final String GET = "get";

  /** The processor under test; its store is shared across the calls of one test. */
  private final KvStoreProcessor processor = new KvStoreProcessor();

  private Object run(final String operation, final Map<String, Object> payload) {
    final Map<String, Object> config = Map.of("op", operation, "namespace", "ns");
    final var out =
        processor
            .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), payload)), config)
            .blockFirst();
    return ((Map<?, ?>) java.util.Objects.requireNonNull(out).payload()).get(VALUE);
  }

  @Test
  void putGetDelete_roundTrip() {
    assertThat(run(GET, Map.of(KEY, "a"))).isNull();
    assertThat(run(PUT, Map.of(KEY, "a", VALUE, "x"))).isEqualTo("x");
    assertThat(run(GET, Map.of(KEY, "a"))).isEqualTo("x");
    assertThat(run("delete", Map.of(KEY, "a"))).isEqualTo("x");
    assertThat(run(GET, Map.of(KEY, "a"))).isNull();
  }

  @Test
  void withoutNamespace_eachWorkflowHasItsOwnKeys() {
    final Map<String, Object> config = Map.of("op", INCR, KEY, "counter");
    final var first = DefaultMessage.create(UUID.randomUUID(), "x").withWorkflowId("wf-a");
    final var second = DefaultMessage.create(UUID.randomUUID(), "x").withWorkflowId("wf-b");

    processor.process(Flux.just(first), config).blockFirst();
    final var again = processor.process(Flux.just(first), config).blockFirst();
    final var other = processor.process(Flux.just(second), config).blockFirst();

    assertThat(((Map<?, ?>) java.util.Objects.requireNonNull(again).payload()).get(VALUE))
        .isEqualTo(2L);
    assertThat(((Map<?, ?>) java.util.Objects.requireNonNull(other).payload()).get(VALUE))
        .isEqualTo(1L);
  }

  @Test
  void initialize_inMemoryStore_warnsOnlyOnce() {
    StepVerifier.create(processor.initialize(Map.of("op", GET))).verifyComplete();
    StepVerifier.create(processor.initialize(Map.of("op", GET))).verifyComplete();
  }

  @Test
  void initialize_durableStore_doesNotWarn() {
    final KvStoreProcessor durable =
        new KvStoreProcessor(
            org.mockito.Mockito.mock(com.infenia.yukta.plugin.store.KeyValueStore.class));

    StepVerifier.create(durable.initialize(Map.of("op", GET))).verifyComplete();
  }

  @Test
  void incr_countsFromOneOrByValue() {
    assertThat(run(INCR, Map.of(KEY, "c"))).isEqualTo(1L);
    assertThat(run(INCR, Map.of(KEY, "c"))).isEqualTo(2L);
    assertThat(run(INCR, Map.of(KEY, "c", VALUE, 5))).isEqualTo(7L);
  }

  @Test
  void configKey_worksWithNonMapPayload() {
    final var out =
        processor
            .process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "text")),
                Map.of("op", INCR, KEY, "fixed"))
            .blockFirst();
    assertThat(((Map<?, ?>) java.util.Objects.requireNonNull(out).payload()).get(KEY))
        .isEqualTo("fixed");
  }

  @Test
  void incr_rejectsNonNumericStateAndOperand() {
    run(PUT, Map.of(KEY, "s", VALUE, "x"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> run(INCR, Map.of(KEY, "s")));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> run(INCR, Map.of(KEY, "n", VALUE, "2")));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> run(INCR, Map.of(KEY, "n", VALUE, 1.5)));
  }

  @Test
  void newKeysBeyondCapacityAreRejected() {
    final Map<String, Object> config = Map.of("op", PUT, "maxEntries", 1);
    processor
        .process(
            Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of(KEY, "a", VALUE, 1))), config)
        .blockFirst();
    final var second =
        Flux.<com.infenia.yukta.message.Message<?>>just(
            DefaultMessage.create(UUID.randomUUID(), Map.of(KEY, "b", VALUE, 1)));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> processor.process(second, config).blockFirst());
    // existing keys can still be overwritten
    processor
        .process(
            Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of(KEY, "a", VALUE, 2))), config)
        .blockFirst();
  }

  @Test
  void missingKeyOrValue_errors() {
    final Map<String, Object> noValue = new HashMap<>(Map.of(KEY, "k"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> run(PUT, noValue));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> run(GET, Map.of()));
  }

  @Test
  void metadataAndValidation() {
    assertThat(processor.getType()).isEqualTo("KV_STORE");
    assertThat(processor.describe().usagePattern()).contains(INCR);
    StepVerifier.create(processor.validateConfig(Map.of("op", GET))).verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of("op", "nope")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void describe_processorProvidesNodeDesign() {
    assertThat(processor.describe().uiDesign())
        .hasValueSatisfying(
            design -> {
              assertThat(design.label()).isNotBlank();
              assertThat(design.width()).isEqualTo(140);
              assertThat(design.height()).isEqualTo(80);
            });
  }

  @Test
  void unknownOperationWithPutsHashCode_fallsBackToGet() {
    // "pvU" has the same hashCode as "put", so it reaches the switch's equals check and misses it
    assertThat(run("pvU", Map.of(KEY, "a"))).isNull();
    // likewise "jOcr" collides with "incr" and "demFte" with "delete"
    assertThat(run("jOcr", Map.of(KEY, "a"))).isNull();
    assertThat(run("demFte", Map.of(KEY, "a"))).isNull();
  }

  @Test
  void incr_acceptsIntegerAndLongOperandsAndStoredValues() {
    run(PUT, Map.of(KEY, "i", VALUE, 5));
    assertThat(run(INCR, Map.of(KEY, "i", VALUE, 2L))).isEqualTo(7L);
    assertThat(run(INCR, Map.of(KEY, "i", VALUE, 3))).isEqualTo(10L);
    run(PUT, Map.of(KEY, "l", VALUE, 5L));
    assertThat(run(INCR, Map.of(KEY, "l", VALUE, 1))).isEqualTo(6L);
  }

  @Test
  void springConstructor_usesTheApplicationsStoreOrKeepsTheValuesInMemory() {
    final com.infenia.yukta.plugin.store.KeyValueStore other = new InMemoryKeyValueStore();
    final org.springframework.beans.factory.support.StaticListableBeanFactory factory =
        new org.springframework.beans.factory.support.StaticListableBeanFactory();
    factory.addBean("kv", other);
    final KvStoreProcessor shared =
        new KvStoreProcessor(
            factory.getBeanProvider(com.infenia.yukta.plugin.store.KeyValueStore.class));
    final KvStoreProcessor alone =
        new KvStoreProcessor(
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(com.infenia.yukta.plugin.store.KeyValueStore.class));
    final Map<String, Object> config = Map.of("op", PUT);

    shared
        .process(
            Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of(KEY, "x", VALUE, 1))), config)
        .blockFirst();
    alone
        .process(
            Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of(KEY, "y", VALUE, 2))), config)
        .blockFirst();

    assertThat(other.get(":x").block()).isEqualTo(1);
    assertThat(other.get(":y").block()).isNull();
  }
}
