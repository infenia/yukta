// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the behaviour described by {@link SupervisorScope}. */
@NoArgsConstructor
class SupervisorScopeTest {

  @Test
  void globalMatchesEverythingIncludingCommandsWithoutARun() {
    assertThat(new SupervisorScope.Global().matches(null, null)).isTrue();
    assertThat(new SupervisorScope.Global().matches("s", "w")).isTrue();
  }

  @Test
  void sessionMatchesOnlyItsSession() {
    final SupervisorScope scope = new SupervisorScope.Session("A");

    assertThat(scope.matches("A", "w")).isTrue();
    assertThat(scope.matches("B", "w")).isFalse();
    assertThat(scope.matches(null, null)).isFalse();
    assertThat(scope.kind()).isEqualTo(SupervisorScope.Kind.SESSION);
  }

  @Test
  void workflowMatchesItsWorkflowAndOptionallyItsSession() {
    assertThat(new SupervisorScope.Workflow("w", null).matches("any", "w")).isTrue();
    assertThat(new SupervisorScope.Workflow("w", null).matches("any", "x")).isFalse();
    assertThat(new SupervisorScope.Workflow("w", "A").matches("A", "w")).isTrue();
    assertThat(new SupervisorScope.Workflow("w", "A").matches("B", "w")).isFalse();
    assertThat(new SupervisorScope.Workflow("w", "A").matches(null, null)).isFalse();
  }
}
