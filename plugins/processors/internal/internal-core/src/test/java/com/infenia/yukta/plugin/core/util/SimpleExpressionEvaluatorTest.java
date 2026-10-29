// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.TooManyMethods")
class SimpleExpressionEvaluatorTest {

  private static Message<?> msg(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload).withHeader("src", "rest");
  }

  @Test
  void cacheIsBoundedAndEvictedExpressionsAreParsedAgain() {
    for (int i = 0; i < 1_500; i++) {
      SimpleExpressionEvaluator.preParse("payload.a" + i + " exists");
    }

    assertThat(SimpleExpressionEvaluator.cacheSize()).isLessThanOrEqualTo(1_000L);
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a0 exists", msg(Map.of("a0", 1))))
        .isTrue();
  }

  @Test
  void equalsOnNestedPayloadWithQuotes() {
    final Message<?> m = msg(Map.of("a", Map.of("b", "x")));
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a.b == 'x'", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a.b == \"y\"", m)).isFalse();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a.b == x", m)).isTrue();
  }

  @Test
  void equalsOnMetadataAndWholePayload() {
    final Message<?> m = msg("hello");
    assertThat(SimpleExpressionEvaluator.evaluate("metadata.src == rest", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("payload == hello", m)).isTrue();
  }

  @Test
  void missingValueEqualsOnlyNullOperand() {
    final Message<?> m = msg(Map.of("a", 1));
    assertThat(SimpleExpressionEvaluator.evaluate("payload.zzz == 'x'", m)).isFalse();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.zzz ==", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a ==", m)).isFalse();
  }

  @Test
  void unknownRootAndNonMapPayloadResolveToNull() {
    assertThat(SimpleExpressionEvaluator.evaluate("other.x exists", msg(Map.of("x", 1)))).isFalse();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.x exists", msg("str"))).isFalse();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a.b exists", msg(Map.of("c", 1))))
        .isFalse();
  }

  @Test
  void exists() {
    final Message<?> m = msg(Map.of("a", 1));
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a exists", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("metadata.src exists", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("metadata.none exists", m)).isFalse();
  }

  @Test
  void matches() {
    final Message<?> m = msg(Map.of("a", "abc123"));
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a matches '[a-z]+\\d+'", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a matches 'x.*'", m)).isFalse();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.nope matches '.*'", m)).isFalse();
  }

  @Test
  void invalidExpressionIsRejected() {
    assertThatThrownBy(() -> SimpleExpressionEvaluator.evaluate("garbage", msg("x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid SIMPLE expression");
  }

  @Test
  void preParseHandlesNullBlankAndValid() {
    SimpleExpressionEvaluator.preParse(null);
    SimpleExpressionEvaluator.preParse("  ");
    SimpleExpressionEvaluator.preParse("payload.pp exists");
    assertThat(SimpleExpressionEvaluator.evaluate("payload.pp exists", msg(Map.of("pp", 1))))
        .isTrue();
  }

  @Test
  void unbalancedQuotesAreKeptLiteral() {
    final Message<?> m = msg(Map.of("a", "'x", "b", "x'", "c", "\"x", "d", "x\""));
    assertThat(SimpleExpressionEvaluator.evaluate("payload.a == 'x", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.b == x'", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.c == \"x", m)).isTrue();
    assertThat(SimpleExpressionEvaluator.evaluate("payload.d == x\"", m)).isTrue();
  }
}
