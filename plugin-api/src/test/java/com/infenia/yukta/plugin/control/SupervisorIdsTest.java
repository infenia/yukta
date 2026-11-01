// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link SupervisorIds}. */
@NoArgsConstructor
class SupervisorIdsTest {

  @Test
  void validIdsPass() {
    for (final String id :
        new String[] {"a", "rules", "my.sup_1-x", "A".repeat(64), "..", "-", "1"}) {
      assertThat(SupervisorIds.isValid(id)).as(id).isTrue();
      assertThat(SupervisorIds.validate(id)).isEqualTo(id);
    }
  }

  @Test
  void invalidIdsAreRejected() {
    for (final String id :
        new String[] {
          null, "", "A".repeat(65), "key:admin", "token:x", "a b", " a", "a\n", "a\tb", "é", "a/b"
        }) {
      assertThat(SupervisorIds.isValid(id)).as(String.valueOf(id)).isFalse();
      assertThatThrownBy(() -> SupervisorIds.validate(id, "binding id"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("binding id")
          .hasMessageContaining(SupervisorIds.RULE);
    }
  }

  @Test
  void messageShowsControlCharactersEscaped() {
    assertThatThrownBy(() -> SupervisorIds.validate("a\nb"))
        .hasMessageContaining("a\\u000ab")
        .hasMessageNotContaining("\n");
  }
}
