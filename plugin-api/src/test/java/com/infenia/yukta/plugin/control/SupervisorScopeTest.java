// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the behaviour described by {@link SupervisorScope}. */
@NoArgsConstructor
class SupervisorScopeTest {

  @Test
  void globalMatchesEverythingAndCommandsWithoutRun() {
    assertThat(new SupervisorScope.Global().matches(null, null)).isTrue();
    assertThat(new SupervisorScope.Global().matches("s", "w")).isTrue();
  }

  @Test
  void describesItselfByKindSessionAndWorkflow() {
    final SupervisorScope global = new SupervisorScope.Global();
    final SupervisorScope session = new SupervisorScope.Session("A");
    final SupervisorScope workflow = new SupervisorScope.Workflow("w", "A");

    assertThat(global.kind()).isEqualTo(SupervisorScope.Kind.GLOBAL);
    assertThat(global.sessionId()).isNull();
    assertThat(global.workflowId()).isNull();
    assertThat(session.sessionId()).isEqualTo("A");
    assertThat(session.workflowId()).isNull();
    assertThat(workflow.kind()).isEqualTo(SupervisorScope.Kind.WORKFLOW);
    assertThat(workflow.sessionId()).isEqualTo("A");
    assertThat(workflow.workflowId()).isEqualTo("w");
  }

  @Test
  void rejectsMissingIds() {
    assertThatThrownBy(() -> new SupervisorScope.Session(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SupervisorScope.Workflow(null, null))
        .isInstanceOf(NullPointerException.class);
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
