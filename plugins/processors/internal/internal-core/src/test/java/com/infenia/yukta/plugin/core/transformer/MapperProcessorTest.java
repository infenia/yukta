// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class MapperProcessorTest {

  @Test
  void scriptModeIsRejectedWithoutScriptEngine() {
    final var mapper = new MapperProcessor();

    StepVerifier.create(mapper.validateConfig(Map.of("mode", "SCRIPT", "mapping", "1")))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("script-js"))
        .verify();
  }

  @Test
  void projectionModeStillValidatesWithoutScriptEngine() {
    StepVerifier.create(
            new MapperProcessor().validateConfig(Map.of("mode", "PROJECTION", "mapping", Map.of())))
        .verifyComplete();
  }
}
