// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link SupervisorProfile}, {@link SupervisorBinding} and the text form of a scope. */
@NoArgsConstructor
class SupervisorBindingTest {

  @Test
  void scopeKeysRoundTrip() {
    for (final String key : new String[] {"global", "session:a", "workflow:w", "workflow:a/w"}) {
      assertThat(SupervisorScope.parse(key).key()).isEqualTo(key);
    }
    assertThat(SupervisorScope.parse(" session:a ")).isEqualTo(new SupervisorScope.Session("a"));
    assertThat(SupervisorScope.parse("workflow:a/w"))
        .isEqualTo(new SupervisorScope.Workflow("w", "a"));
  }

  @Test
  void badScopesAreRejected() {
    for (final String bad :
        new String[] {null, "", "everything", "session:", "run:x", "workflow:/w", "workflow:a/"}) {
      assertThatThrownBy(() -> SupervisorScope.parse(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void profileKeepsAnImmutableCopyOfItsSettingsAndAllowsNullValues() {
    final Map<String, Object> settings = Collections.singletonMap("k", null);
    final SupervisorProfile profile = new SupervisorProfile("p", "rule-supervisor", settings, true);

    assertThat(profile.settings()).containsOnlyKeys("k");
    assertThatThrownBy(() -> profile.settings().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(new SupervisorProfile("p", "t", null, false).settings()).isEmpty();
    assertThatThrownBy(() -> new SupervisorProfile(null, "t", Map.of(), false))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SupervisorProfile("p", null, Map.of(), false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void bindingNeedsIdProfileAndScope() {
    final SupervisorBinding binding =
        new SupervisorBinding("b", "p", new SupervisorScope.Session("a"), true);

    assertThat(binding.enabled()).isTrue();
    assertThatThrownBy(() -> new SupervisorBinding(null, "p", new SupervisorScope.Global(), true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SupervisorBinding("b", null, new SupervisorScope.Global(), true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SupervisorBinding("b", "p", null, true))
        .isInstanceOf(NullPointerException.class);
  }
}
