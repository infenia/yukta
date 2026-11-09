// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The readers of the old guard settings: strict about their form, plain about their meaning. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class LegacyHelpersTest {

  private enum Action {
    PAUSE,
    STOP
  }

  @Test
  void positiveNumberIsWholeNumberOfAtLeastOne() {
    assertThat(LegacySettings.positive(Map.of(), "k", 7)).isEqualTo(7);
    assertThat(LegacySettings.positive(Map.of("k", 3), "k", 7)).isEqualTo(3);
    assertThat(LegacySettings.positive(Map.of("k", 3.0), "k", 7)).isEqualTo(3);
    for (final Object bad : new Object[] {0, 1.5, "3", -2, 3_000_000_000L}) {
      assertThatThrownBy(() -> LegacySettings.positive(Map.of("k", bad), "k", 7))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("k must be a whole number");
    }
    assertThat(LegacySettings.optionalPositive(Map.of(), "k")).isNull();
    assertThat(LegacySettings.optionalPositive(Map.of("k", 4), "k")).isEqualTo(4);
  }

  @Test
  void choiceIsReadInAnyCaseAndNamesTheOptionsWhenWrong() {
    assertThat(LegacySettings.choice(Map.of(), "a", Action.class, Action.STOP))
        .isEqualTo(Action.STOP);
    assertThat(LegacySettings.choice(Map.of("a", " pause "), "a", Action.class, Action.STOP))
        .isEqualTo(Action.PAUSE);
    assertThatThrownBy(
            () -> LegacySettings.choice(Map.of("a", "kill"), "a", Action.class, Action.STOP))
        .hasMessageContaining("a must be one of [PAUSE, STOP]");
  }

  @Test
  void textAndListsAreNullWhenNotGivenAndRefusedWhenOfAnotherForm() {
    assertThat(LegacySettings.text(Map.of(), "t")).isNull();
    assertThat(LegacySettings.text(Map.of("t", "x"), "t")).isEqualTo("x");
    assertThatThrownBy(() -> LegacySettings.text(Map.of("t", " "), "t"))
        .hasMessageContaining("t must be non-blank text");
    assertThatThrownBy(() -> LegacySettings.text(Map.of("t", 5), "t"))
        .hasMessageContaining("t must be non-blank text");

    assertThat(LegacySettings.list(Map.of(), "l")).isNull();
    assertThat(LegacySettings.list(Map.of("l", List.of()), "l")).isNull();
    assertThat(LegacySettings.list(Map.of("l", List.of("a")), "l")).hasSize(1);
    assertThatThrownBy(() -> LegacySettings.list(Map.of("l", "a"), "l"))
        .hasMessageContaining("l must be a list");
  }

  @Test
  void selectorsBecomeConditionOnTheNodeId() {
    assertThat(LegacySelectors.condition(List.of("build.*", "deploy")))
        .isEqualTo("nodeId != null and (nodeId matches '^\\Qbuild.\\E.*' or nodeId == 'deploy')");
    assertThat(LegacySelectors.literal("it's")).isEqualTo("'it''s'");
    for (final Object bad : new Object[] {7, " ", "a,b", ".*"}) {
      assertThatThrownBy(() -> LegacySelectors.condition(List.of(bad)))
          .hasMessageContaining("nodes must be node ids or <prefix>.*");
    }
  }

  @Test
  void nodeIsSelectedByIdOrByPrefixAndEverythingWhenNoneAreGiven() {
    assertThat(LegacySelectors.selects(null, "any")).isTrue();
    assertThat(LegacySelectors.selects(List.of(), "any")).isTrue();
    assertThat(LegacySelectors.selects(List.of("build.*"), "build.compile")).isTrue();
    assertThat(LegacySelectors.selects(List.of("build.*"), "deploy")).isFalse();
    assertThat(LegacySelectors.selects(List.of("deploy"), "deploy")).isTrue();
    assertThat(LegacySelectors.selects(List.of("deploy"), "deploy.prod")).isFalse();
  }

  @Test
  void ruleSetCanBeBuiltFromRulesAlone() {
    assertThat(new RuleSet(List.of()).rules()).isEmpty();
  }
}
