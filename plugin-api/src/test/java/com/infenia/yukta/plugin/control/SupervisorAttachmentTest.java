// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link Supervisor}, {@link SupervisorAttachment} and the text form of a scope. */
@NoArgsConstructor
class SupervisorAttachmentTest {

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
    final Supervisor profile = new Supervisor("p", "rule-supervisor", settings, true);

    assertThat(profile.settings()).containsOnlyKeys("k");
    assertThatThrownBy(() -> profile.settings().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(new Supervisor("p", "t", null, false).settings()).isEmpty();
    assertThatThrownBy(() -> new Supervisor(null, "t", Map.of(), false))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Supervisor("p", null, Map.of(), false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void bindingNeedsIdProfileAndScope() {
    final SupervisorAttachment binding =
        new SupervisorAttachment("b", "p", new SupervisorScope.Session("a"), true);

    assertThat(binding.enabled()).isTrue();
    assertThatThrownBy(
            () -> new SupervisorAttachment(null, "p", new SupervisorScope.Global(), true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new SupervisorAttachment("b", null, new SupervisorScope.Global(), true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SupervisorAttachment("b", "p", null, true))
        .isInstanceOf(NullPointerException.class);
  }
}
