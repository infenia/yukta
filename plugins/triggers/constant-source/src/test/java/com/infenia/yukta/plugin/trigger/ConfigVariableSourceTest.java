// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Config variable source test. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestAssertionsShouldIncludeMessage",
  "PMD.UnnecessaryFullyQualifiedName"
})
@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
class ConfigVariableSourceTest {

  /** Test literal val. */
  private static final String VAL = "val";

  /** Test literal resolved-val. */
  private static final String RESOLVED_VAL = "resolved-val";

  /** Test literal key. */
  private static final String KEY = "key";

  /** Resolver mock. */
  @Mock private VariableResolver resolver;

  /** Source under test. */
  private ConfigVariableSource source;

  @BeforeEach
  void setUp() {
    source = new ConfigVariableSource(resolver);
  }

  @Test
  void describesItself() {
    assertTrue(
        source.describe().description().contains("predefined variables"), "Describes itself");
    assertTrue(source.describe().usagePattern().contains("variables"), "Describes itself (2)");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testStartWithPayloadTarget() {
    when(resolver.isStatic(any())).thenReturn(false);
    when(resolver.resolve(VAL)).thenReturn(Mono.just(RESOLVED_VAL));

    final Map<String, Object> config = Map.of("target", "PAYLOAD", "variables", Map.of(KEY, VAL));

    source.initialize(config).block();

    StepVerifier.create(source.start(config).contextWrite(ctx -> ctx.put("payload", Map.of())))
        .assertNext(
            message -> {
              assertTrue(message.payload() instanceof Map, "Test start with payload target");
              final Map<String, Object> payload = (Map<String, Object>) message.payload();
              assertEquals(RESOLVED_VAL, payload.get(KEY), "Test start with payload target (2)");
              assertTrue(message.metadata().isEmpty(), "Test start with payload target (3)");
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testStartWithMetadataTarget() {
    when(resolver.isStatic(any())).thenReturn(false);
    when(resolver.resolve(VAL)).thenReturn(Mono.just(RESOLVED_VAL));

    final Map<String, Object> config = Map.of("target", "METADATA", "variables", Map.of(KEY, VAL));

    source.initialize(config).block();

    StepVerifier.create(source.start(config).contextWrite(ctx -> ctx.put("payload", Map.of())))
        .assertNext(
            message -> {
              assertEquals(
                  RESOLVED_VAL, message.metadata().get(KEY), "Test start with metadata target");
              assertTrue(
                  ((Map) message.payload()).isEmpty(), "Test start with metadata target (2)");
            })
        .verifyComplete();
  }

  @Test
  void typeIsConstantSource() {
    assertEquals("CONSTANT_SOURCE", source.getType());
  }

  @Test
  void staticValuesAreResolvedOnceInPrepareAndServedFromCache() {
    when(resolver.isStatic("static")).thenReturn(true);
    when(resolver.isStatic("dyn")).thenReturn(false);
    when(resolver.resolve("static")).thenReturn(Mono.just("S"));
    when(resolver.resolve("dyn")).thenReturn(Mono.just("D"));

    final Map<String, Object> config = Map.of("variables", Map.of("a.b", "static", "c", "dyn"));

    StepVerifier.create(source.prepare(config)).verifyComplete();
    StepVerifier.create(source.start(config))
        .assertNext(
            message -> {
              final Map<?, ?> payload = (Map<?, ?>) message.payload();
              assertEquals(Map.of("b", "S"), payload.get("a"));
              assertEquals("D", payload.get("c"));
              assertEquals("default", message.sourcePort());
            })
        .verifyComplete();
    verify(resolver, times(1)).resolve("static");
  }

  @Test
  void staticValueNotPreparedFallsBackToRawValue() {
    when(resolver.isStatic("static")).thenReturn(true);
    StepVerifier.create(source.start(Map.of("variables", Map.of("k", "static"))))
        .assertNext(m -> assertEquals(Map.of("k", "static"), m.payload()))
        .verifyComplete();
  }

  @Test
  void emptyConfigEmitsEmptyPayloadAndPreparesNothing() {
    StepVerifier.create(source.prepare(Map.of())).verifyComplete();
    StepVerifier.create(source.start(Map.of()))
        .assertNext(m -> assertTrue(((Map<?, ?>) m.payload()).isEmpty()))
        .verifyComplete();
  }

  @Test
  void metadataTargetIsCaseInsensitive() {
    when(resolver.isStatic(any())).thenReturn(false);
    when(resolver.resolve("v")).thenReturn(Mono.just("r"));
    StepVerifier.create(source.start(Map.of("target", "metadata", "variables", Map.of("x.y", "v"))))
        .assertNext(m -> assertEquals(Map.of("y", "r"), m.metadata().get("x")))
        .verifyComplete();
  }

  @org.junit.jupiter.api.Test
  void declaresConfigSchema() {
    final var schema = source.getConfigSchema();
    org.junit.jupiter.api.Assertions.assertTrue(
        schema.stream().anyMatch(f -> !f.required() && "variables".equals(f.name())));
  }

  @Test
  void emptyConfigEmitsOneEmptyMessage() {
    StepVerifier.create(source.start(Map.of()))
        .assertNext(m -> assertEquals(Map.of(), m.payload()))
        .verifyComplete();
  }
}
