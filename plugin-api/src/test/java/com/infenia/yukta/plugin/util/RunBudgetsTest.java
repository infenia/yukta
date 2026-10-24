// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CommentRequired"})
class RunBudgetsTest {

  private final RunBudgets budgets = new RunBudgets();

  @Test
  void runWithoutUse_orWithoutLimits_mayCall() {
    assertThat(budgets.exceeded("r", 10L, 1.0)).isEmpty();
    budgets.add("r", 1_000, 50.0);
    assertThat(budgets.exceeded("r", null, null)).isEmpty();
  }

  @Test
  void tokensAndCostAreLimitedSeparately() {
    budgets.add("r", 90, 0.5);
    assertThat(budgets.exceeded("r", 100L, 1.0)).isEmpty();

    budgets.add("r", 10, 0.5);
    assertThat(budgets.exceeded("r", 100L, 5.0))
        .hasValueSatisfying(w -> assertThat(w).contains("token"));
    assertThat(budgets.exceeded("r", 500L, 1.0))
        .hasValueSatisfying(w -> assertThat(w).contains("cost"));
  }

  @Test
  void onlyTheNewestRunsAreRemembered() {
    budgets.add("first", 100, 0);
    for (int i = 0; i < RunBudgets.MAX_RUNS; i++) {
      budgets.add("run-" + i, 1, 0);
    }

    assertThat(budgets.exceeded("first", 100L, null)).isEmpty();
    assertThat(budgets.exceeded("run-" + (RunBudgets.MAX_RUNS - 1), 1L, null)).isPresent();
  }

  @Test
  void activeRunIsNeverEvicted() {
    budgets.add("busy", 1, 0);
    for (int i = 0; i < RunBudgets.MAX_RUNS + 5; i++) {
      budgets.add("run-" + i, 1, 0);
      budgets.add("busy", 1, 0);
    }

    assertThat(budgets.exceeded("busy", 1L, null)).isPresent();
  }

  @Test
  void fractionalLimitRoundsUpInsteadOfTrippingAtOnce() {
    assertThat(budgets.exceeded("fresh", Map.of("maxTokens", 0.5, "maxCalls", 0.5))).isEmpty();
  }

  @Test
  void callsAreLimited_andAnAgentIsCountedApart() {
    final Map<String, Object> budget = Map.of("maxCalls", 2, "agent", "a1");
    final String key = RunBudgets.key("r", budget);
    budgets.addCall(key);
    assertThat(budgets.exceeded(key, budget)).isEmpty();
    budgets.addCall(key);

    assertThat(budgets.exceeded(key, budget))
        .hasValueSatisfying(w -> assertThat(w).contains("call"));
    assertThat(RunBudgets.key("r", Map.of("maxCalls", 2))).isEqualTo("r");
    assertThat(budgets.exceeded("r", Map.of("maxCalls", 2))).isEmpty();
  }

  @Test
  void budgetOption_isReadForAllThreeLimits_andTheLedgerIsShared() {
    final Map<String, Object> budget = Map.of("maxTokens", 10, "maxCost", 2.5, "maxCalls", 3);
    budgets.add("opt", 10, 0.0);
    assertThat(budgets.exceeded("opt", budget))
        .hasValueSatisfying(w -> assertThat(w).contains("token"));
    assertThat(budgets.exceeded("fresh", budget)).isEmpty();
    assertThat(budgets.exceeded("opt", Map.of("maxTokens", 99))).isEmpty();
    assertThat(RunBudgets.shared()).isSameAs(RunBudgets.shared());
    assertThat(RunBudgets.key("r", null)).isEqualTo("r");
    assertThat(RunBudgets.key("r", Map.of("agent", " "))).isEqualTo("r");
    assertThat(RunBudgets.key("r", Map.of("agent", "a"))).isEqualTo("r\0a");
  }

  @Test
  void validate_acceptsGoodBudgets_andRefusesBadOnes() {
    RunBudgets.validate(Map.of());
    RunBudgets.validate(Map.of("budget", "not a map"));
    RunBudgets.validate(
        Map.of("budget", Map.of("maxCalls", 2, "maxTokens", 5, "agent", "a", "hardStop", true)));
    RunBudgets.validate(Map.of("budget", Map.of("maxCost", 1.0), "prices", Map.of("m", 1)));

    for (final Map<String, Object> bad :
        java.util.List.<Map<String, Object>>of(
            Map.of("budget", Map.of("maxCalls", 0)),
            Map.of("budget", Map.of("maxTokens", "lots")),
            Map.of("budget", Map.of("maxCost", 1.0)),
            Map.of("budget", Map.of("agent", 5)),
            Map.of("budget", Map.of("hardStop", "yes")))) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> RunBudgets.validate(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
