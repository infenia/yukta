// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** An expression that runs too long is stopped, not left to hold a thread. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
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
  void runawayRegexInMatchesIsStopped() {
    assertStopped(EVIL_INPUT + ".matches(" + EVIL_REGEX + ")", Map.of());
  }

  @Test
  void runawayRegexInSplitIsStopped() {
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
  void timeoutOfZeroTurnsTheLimitOff() throws InterruptedException {
    System.setProperty("yukta.spel.timeout-ms", "0");

    final String result =
        SpelBudget.run(
            () -> {
              sleep(60);
              SpelBudget.check();
              return "ok";
            });

    assertThat(result).isEqualTo("ok");
  }

  @Test
  void anInnerEvaluationRestoresTheDeadlineOfTheOuterOne() {
    System.setProperty("yukta.spel.timeout-ms", "50");

    SpelBudget.run(
        () -> {
          SpelBudget.run(() -> "inner");
          sleep(120);
          assertThatThrownBy(SpelBudget::check).isInstanceOf(SpelBudget.Exceeded.class);
          return null;
        });

    assertThatCode(SpelBudget::check)
        .as("no deadline outside an evaluation")
        .doesNotThrowAnyException();
  }

  @Test
  void exceededLooksThroughTheCauseChainButNotForever() {
    assertThat(SpelBudget.exceeded(new RuntimeException(new SpelBudget.Exceeded()))).isTrue();
    assertThat(SpelBudget.exceeded(new RuntimeException("plain"))).isFalse();

    Throwable deep = new SpelBudget.Exceeded();
    for (int i = 0; i < 10; i++) {
      deep = new RuntimeException(deep);
    }
    assertThat(SpelBudget.exceeded(deep)).as("too deep to look for").isFalse();
  }

  @Test
  void methodsThatAreNotRegexCallsRunAsBefore() {
    final Map<String, Object> list = Map.of("items", List.of("a", "b"));

    assertThat((Integer) SpelUtils.evaluateSync("'abc'.length()", Map.of())).isEqualTo(3);
    assertThat((Boolean) SpelUtils.evaluateSync("'abc'.startsWith('a')", Map.of())).isTrue();
    assertThat((Integer) SpelUtils.evaluateSync("'abcb'.indexOf('b', 2)", Map.of())).isEqualTo(3);
    assertThat((Boolean) SpelUtils.evaluateSync("items.contains('a')", list)).isTrue();
    assertThat(SpelUtils.<Object>evaluateSync("'a b'.split(' ')", Map.of()))
        .isEqualTo(new String[] {"a", "b"});
    assertThatThrownBy(() -> SpelUtils.evaluateSync("'abc'.matches(null)", Map.of()))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void mapRootCanStillBeAssignedTo() {
    final Map<String, Object> root = new HashMap<>(Map.of("x", 1));

    SpelUtils.evaluateSync("x = 5", root);

    assertThat(root).containsEntry("x", 5);
  }

  private static void sleep(final long millis) {
    try {
      // the wall-clock budget under test
      Thread.sleep(millis); // nosem: yukta-test-thread-sleep
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void theBudgetIsPerEvaluation() {
    for (int i = 0; i < 3; i++) {
      assertThat((Boolean) SpelUtils.evaluateSync("'abc'.matches('abc')", Map.of())).isTrue();
    }
  }
}
