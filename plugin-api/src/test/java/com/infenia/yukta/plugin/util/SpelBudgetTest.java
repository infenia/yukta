// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** An expression that runs too long is stopped, not left to hold a thread. */
@NoArgsConstructor
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SpelBudgetTest {

  /** A text that sends the regex below into exponential time (checked on the JDK in use). */
  private static final String EVIL_INPUT = "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab'";

  /** The regex that is slow on that text. */
  private static final String EVIL_REGEX = "'(.*a){20}$'";

  @BeforeEach
  void shortBudget() {
    System.setProperty("yukta.spel.timeout-ms", "300");
  }

  @AfterEach
  void defaultBudget() {
    System.clearProperty("yukta.spel.timeout-ms");
  }

  private static void assertStopped(final String expression, final Object root) {
    final long start = System.nanoTime();

    assertThatThrownBy(() -> SpelUtils.evaluateSync(expression, root))
        .isInstanceOf(WorkflowExecutionException.class)
        .hasMessageContaining("time budget of 300 ms");

    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .as("stopped soon after the budget, not after the work")
        .isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void aRunawayRegexInMatchesIsStopped() {
    assertStopped(EVIL_INPUT + ".matches(" + EVIL_REGEX + ")", Map.of());
  }

  @Test
  void aRunawayRegexInSplitIsStopped() {
    assertStopped(EVIL_INPUT + ".split(" + EVIL_REGEX + ")", Map.of());
  }

  @Test
  void ordinaryExpressionsAreNotAffected() {
    assertThat((Boolean) SpelUtils.evaluateSync("'abc'.matches('a.c')", Map.of())).isTrue();
    assertThat(SpelUtils.<Object>evaluateSync("'a,b,c'.split(',')", Map.of()))
        .isEqualTo(new String[] {"a", "b", "c"});
    assertThat(SpelUtils.<Object>evaluateSync("'a,b,,'.split(',', 3)", Map.of()))
        .isEqualTo(new String[] {"a", "b", ","});
    assertThat((Boolean) SpelUtils.evaluateSync("'abc'.matches(#r)", Map.of(), Map.of("r", "x")))
        .isFalse();
  }

  @Test
  void theBudgetIsPerEvaluation() {
    for (int i = 0; i < 3; i++) {
      assertThat((Boolean) SpelUtils.evaluateSync("'abc'.matches('abc')", Map.of())).isTrue();
    }
  }
}
