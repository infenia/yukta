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
        new String[] {
          null,
          "",
          "everything",
          "session:",
          "run:",
          "run:/w",
          "servers",
          "workflow:/w",
          "workflow:a/"
        }) {
      assertThatThrownBy(() -> SupervisorScope.parse(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void attachmentCanBeMarkedAsConflictAndThenIsDisabled() {
    final SupervisorAttachment declared =
        new SupervisorAttachment(
            "a",
            "p",
            new SupervisorScope.Run("w", "s"),
            true,
            SupervisorSource.DEFINITION,
            "s",
            "w");

    assertThat(declared.conflict()).isFalse();
    final SupervisorAttachment marked = declared.asConflict();
    assertThat(marked.conflict()).isTrue();
    assertThat(marked.enabled()).isFalse();
    assertThat(marked.source()).isEqualTo(SupervisorSource.DEFINITION);
    assertThat(marked.originWorkflowId()).isEqualTo("w");
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

  @Test
  void attachmentsCanBeBuiltWithConflictFlagAndLockedOrNot() {
    final SupervisorAttachment stored =
        new SupervisorAttachment(
            "a",
            "p",
            new SupervisorScope.Run("w", "s"),
            true,
            SupervisorSource.MANUAL,
            null,
            null,
            true);

    assertThat(stored.conflict()).isTrue();
    assertThat(stored.locked()).isFalse();
    final SupervisorAttachment locked = stored.withLocked(true);
    assertThat(locked.locked()).isTrue();
    assertThat(locked.conflict()).isTrue();
    assertThat(locked.withLocked(false).locked()).isFalse();
  }
}
