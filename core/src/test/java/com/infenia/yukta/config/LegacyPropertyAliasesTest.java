// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@NoArgsConstructor
class LegacyPropertyAliasesTest {

  @Test
  void withoutAnyOldName_noAliasSourceIsAdded() {
    final MockEnvironment environment = new MockEnvironment().withProperty("yukta.cache.ttl", "5m");

    new LegacyPropertyAliases().postProcessEnvironment(environment, null);

    assertThat(environment.getPropertySources().contains("yukta-legacy")).isFalse();
    assertThat(environment.getProperty("yukta.cache.ttl")).isEqualTo("5m");
  }

  @Test
  void anOldNameAlone_isReadUnderItsNewName() {
    final MockEnvironment environment =
        new MockEnvironment().withProperty("workflow.cache.max-size", "9");

    new LegacyPropertyAliases().postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("yukta.cache.max-size")).isEqualTo("9");
  }

  @Test
  void newNameThatIsAlsoSetWinsOverTheOldName() {
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty("workflow.cache.max-size", "9")
            .withProperty("yukta.cache.max-size", "20");

    new LegacyPropertyAliases().postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("yukta.cache.max-size")).isEqualTo("20");
  }
}
