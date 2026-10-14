// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.expression.spel.SpelEvaluationException;
import reactor.test.StepVerifier;

class SpelUtilsTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "T(java.lang.System).getProperty('java.version')",
        "new java.io.File('/tmp/x')",
        "@someBean",
        "'a'.getClass().forName('java.lang.Runtime')"
      })
  void rejectsExpressionsThatReachBeyondTheData(final String expression) {
    assertThatThrownBy(() -> SpelUtils.evaluateSync(expression, Map.of()))
        .isInstanceOf(SpelEvaluationException.class);
  }

  @Test
  void readsMapRootsVariablesAndInstanceMethods() {
    final Map<String, Object> root = Map.of("name", "yukta");

    assertThat((String) SpelUtils.evaluateSync("name", root)).isEqualTo("yukta");
    assertThat((Boolean) SpelUtils.evaluateSync("name.startsWith('yu')", root)).isTrue();
    assertThat((Integer) SpelUtils.evaluateSync("#n + 1", root, Map.of("n", 1))).isEqualTo(2);
  }

  @Test
  void timeValuesKeepAllTheirMethods() {
    assertThat(
            (Object)
                SpelUtils.evaluateSync(
                    "#d.plusDays(1)", Map.of(), Map.of("d", java.time.LocalDate.of(2026, 1, 1))))
        .isEqualTo(java.time.LocalDate.of(2026, 1, 2));
  }

  @Test
  void methodsOutsideTheAllowListAreNotFound() {
    final Map<String, Object> root = Map.of("name", "yukta");

    assertThat((Object) SpelUtils.evaluateSync("name.toUpperCase()", root)).isEqualTo("YUKTA");
    for (final String expression :
        new String[] {
          "name.repeat(1000000000)",
          "name.replace('y', 'yyyyyyyyyy').replace('y', 'yyyyyyyyyy')",
          "name.getClass()",
          "name.getBytes()"
        }) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> SpelUtils.evaluateSync(expression, root))
          .isInstanceOf(SpelEvaluationException.class);
    }
  }

  @Test
  void variablesDoNotLeakBetweenCalls() {
    SpelUtils.evaluateSync("#n", Map.of(), Map.of("n", 1));

    assertThat((Object) SpelUtils.evaluateSync("#n", Map.of())).isNull();
  }

  @Test
  void asyncEvaluationUsesTheSameSandbox() {
    StepVerifier.create(SpelUtils.<Object>evaluate("1 + 1", Map.of(), null))
        .expectNext(2)
        .verifyComplete();
    StepVerifier.create(SpelUtils.<Object>evaluate("T(java.lang.System)", Map.of(), null))
        .expectError(SpelEvaluationException.class)
        .verify();
  }

  @Test
  void parseCacheIsBounded() {
    for (int i = 0; i < SpelUtils.MAX_CACHED + 50; i++) {
      SpelUtils.preParse("1 + " + i);
    }
    SpelUtils.preParse(null);
    SpelUtils.preParse(" ");

    assertThat(SpelUtils.cacheSize()).isEqualTo(SpelUtils.MAX_CACHED);
  }
}
