// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
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
}
