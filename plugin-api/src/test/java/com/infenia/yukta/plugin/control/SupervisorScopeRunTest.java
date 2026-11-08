// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the run scope described by {@link SupervisorScope.Run}. */
@NoArgsConstructor
class SupervisorScopeRunTest {

  @Test
  void runMatchesTheRunLevelOfItsWorkflowAndNoNode() {
    final SupervisorScope run = new SupervisorScope.Run("w", "A");

    assertThat(run.kind()).isEqualTo(SupervisorScope.Kind.RUN);
    assertThat(run.sessionId()).isEqualTo("A");
    assertThat(run.workflowId()).isEqualTo("w");
    assertThat(run.matches("A", "w")).isTrue();
    assertThat(run.matches("B", "w")).isFalse();
    assertThat(run.matches("A", "x")).isFalse();
    assertThat(run.matches("A", "w", null)).isTrue();
    assertThat(run.matches("B", "w", null)).isFalse();
    assertThat(run.matches("A", "w", "build.test")).isFalse();
    assertThat(new SupervisorScope.Run("w", null).matches("any", "w")).isTrue();
  }

  @Test
  void runCoversOnlyRunsOfItsWorkflowAndSession() {
    final SupervisorScope inAnySession = new SupervisorScope.Run("w", null);
    final SupervisorScope inA = new SupervisorScope.Run("w", "A");

    assertThat(inAnySession.covers(new SupervisorScope.Run("w", "A"))).isTrue();
    assertThat(inA.covers(new SupervisorScope.Run("w", "A"))).isTrue();
    assertThat(inA.covers(new SupervisorScope.Run("w", "B"))).isFalse();
    assertThat(inA.covers(new SupervisorScope.Run("x", "A"))).isFalse();
    assertThat(inA.covers(new SupervisorScope.Workflow("w", "A"))).isFalse();
  }

  @Test
  void runIsWrittenAndReadAsRun() {
    assertThat(new SupervisorScope.Run("w", "A").key()).isEqualTo("run:A/w");
    assertThat(new SupervisorScope.Run("w", null).key()).isEqualTo("run:w");
    assertThat(SupervisorScope.parse("run:A/w")).isEqualTo(new SupervisorScope.Run("w", "A"));
    assertThat(SupervisorScope.parse("run:w")).isEqualTo(new SupervisorScope.Run("w", null));
    assertThatThrownBy(() -> SupervisorScope.parse("run:"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SupervisorScope.Run(null, "A"))
        .isInstanceOf(NullPointerException.class);
  }
}
