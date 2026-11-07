// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.guards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The readers of guard settings. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SettingsTest {

  @Test
  void emptyListAndAbsentListAreNone() {
    assertThat(Settings.list(Map.of("a", List.of()), "a")).isNull();
    assertThat(Settings.list(Map.of(), "a")).isNull();
    assertThat(Settings.list(Map.of("a", List.of(1)), "a")).hasSize(1);
    assertThatThrownBy(() -> Settings.list(Map.of("a", "x"), "a")).hasMessageContaining("list");
  }

  @Test
  void numbersAreWholeAndAtLeastOne() {
    assertThat(Settings.positive(Map.of("n", 4.0), "n", 9)).isEqualTo(4);
    assertThat(Settings.positive(Map.of(), "n", 9)).isEqualTo(9);
    assertThat(Settings.optionalPositive(Map.of(), "n")).isNull();
    assertThat(Settings.optionalPositive(Map.of("n", 2), "n")).isEqualTo(2);
    assertThatThrownBy(() -> Settings.positive(Map.of("n", "x"), "n", 1)).hasMessageContaining("n");
    assertThatThrownBy(() -> Settings.positive(Map.of("n", 1.5), "n", 1)).hasMessageContaining("n");
    assertThatThrownBy(() -> Settings.positive(Map.of("n", 1e12), "n", 1))
        .hasMessageContaining("n");
  }

  @Test
  void selectorsAreQuotedSoTheyCannotInjectIntoTheCondition() {
    assertThat(Selectors.condition(List.of("a'b")))
        .isEqualTo("nodeId != null and (nodeId == 'a''b')");
    assertThat(Selectors.literal("it's")).isEqualTo("'it''s'");
    assertThatThrownBy(() -> Selectors.condition(List.of(".*"))).hasMessageContaining("nodes");
    assertThatThrownBy(() -> Selectors.condition(List.of(5))).hasMessageContaining("nodes");
    assertThatThrownBy(() -> Selectors.condition(List.of(" "))).hasMessageContaining("nodes");
    assertThatThrownBy(() -> Selectors.condition(List.of("a,b"))).hasMessageContaining("nodes");
  }

  @Test
  void selectorsTakeNodesByIdOrPrefix() {
    assertThat(Selectors.selects(null, "x")).isTrue();
    assertThat(Selectors.selects(List.of(), "x")).isTrue();
    assertThat(Selectors.selects(List.of("a", "b.*"), "a")).isTrue();
    assertThat(Selectors.selects(List.of("a", "b.*"), "b.c")).isTrue();
    assertThat(Selectors.selects(List.of("a", "b.*"), "bc")).isFalse();
    assertThat(Selectors.selects(List.of("a", "b.*"), "ab")).isFalse();
  }
}
