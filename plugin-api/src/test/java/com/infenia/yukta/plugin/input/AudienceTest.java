// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
class AudienceTest {

  @Test
  void acceptsEveryKindOfSelector() {
    final Audience audience =
        new Audience(
            List.of(
                "supervisor:*",
                "supervisor:risk-watch.1",
                "role:OPERATOR",
                "role:ADMIN",
                "key:ops-team",
                "external:slack-ops"));
    assertThat(audience.selectors()).hasSize(6);
    assertThat(audience.isCurrentTier()).isFalse();
  }

  @Test
  void anEmptyAudienceDefersToTheCurrentTier() {
    assertThat(Audience.CURRENT_TIER.isCurrentTier()).isTrue();
    assertThat(new Audience(List.of()).selectors()).isEmpty();
  }

  @Test
  void refusesAnythingElse() {
    for (final String bad :
        List.of(
            "", "supervisor:", "role:VIEWER", "key:", "external:a b", "everyone", "role:admin")) {
      assertThatThrownBy(() -> new Audience(List.of(bad)))
          .as(bad)
          .hasMessageContaining("Not an audience selector");
    }
    assertThatThrownBy(() -> new Audience(java.util.Collections.singletonList(null)))
        .hasMessageContaining("Not an audience selector");
  }

  @Test
  void limitsTheNumberOfSelectors() {
    final List<String> many = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      many.add("supervisor:s" + i);
    }
    assertThatThrownBy(() -> new Audience(many)).hasMessageContaining("at most 20");
  }

  @Test
  void keepsAnImmutableCopy() {
    final List<String> source = new ArrayList<>(List.of("role:ADMIN"));
    final Audience audience = new Audience(source);
    source.add("role:OPERATOR");
    assertThat(audience.selectors()).containsExactly("role:ADMIN");
    assertThatThrownBy(() -> audience.selectors().add("role:OPERATOR"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
