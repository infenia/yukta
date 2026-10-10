// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for ValidateProcessor. */
@NoArgsConstructor
class ValidateProcessorTest {

  /** The processor under test. */
  private final ValidateProcessor processor = new ValidateProcessor();

  /** Shared config: name required, age numeric, tags an array. */
  private final Map<String, Object> config =
      Map.of("required", List.of("name"), "types", Map.of("age", "number", "tags", "array"));

  private Message<?> run(final Object payload) {
    final Message<?> input = DefaultMessage.create(UUID.randomUUID(), payload);
    return processor.process(Flux.just(input), config).blockFirst();
  }

  @Test
  void validPayload_goesToDefault() {
    final Message<?> out = run(Map.of("name", "a", "age", 3, "tags", List.of()));
    assertThat(out.sourcePort()).isEqualTo("default");
  }

  @Test
  void missingAndWrongType_goToInvalidWithReasons() {
    final Message<?> out = run(Map.of("age", "x"));
    assertThat(out.sourcePort()).isEqualTo("invalid");
    assertThat(out.failureReason()).contains("missing field: name").contains("age is not number");
  }

  @Test
  void nonObjectPayload_isInvalid() {
    assertThat(run("text").sourcePort()).isEqualTo("invalid");
  }

  @Test
  void typeChecks_coverAllTypes() {
    final Map<String, Object> all =
        Map.of(
            "types",
            Map.of("s", "string", "n", "number", "b", "boolean", "o", "object", "a", "array"));
    final Message<?> valid =
        processor
            .process(
                Flux.just(
                    DefaultMessage.create(
                        UUID.randomUUID(),
                        Map.of("s", "x", "n", 1, "b", true, "o", Map.of(), "a", List.of()))),
                all)
            .blockFirst();
    assertThat(valid.sourcePort()).isEqualTo("default");
  }

  @Test
  void metadataAndConfigValidation() {
    assertThat(processor.getType()).isEqualTo("VALIDATE");
    assertThat(processor.getOutputPorts()).containsExactly("default", "invalid");
    assertThat(processor.describe().usagePattern()).contains("required");
    StepVerifier.create(processor.validateConfig(config)).verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("types", Map.of("a", "date"))))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
