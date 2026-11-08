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
  void ownsKeepsServerCommandsForTheServerAndAnUnknownRunForNobody() {
    final ExecutionControlCommand pause = new ExecutionControlCommand.PauseWorkflowCommand("e");
    final ExecutionControlCommand limits = new ExecutionControlCommand.SetLimitsCommand(1, 1);
    final SupervisorScope workflow = new SupervisorScope.Workflow("w", "s");

    assertThat(workflow.owns(pause, "s", "w", null)).isTrue();
    assertThat(workflow.owns(pause, null, null, null)).isFalse();
    assertThat(workflow.owns(limits, null, null, null)).isFalse();
    assertThat(new SupervisorScope.Server().owns(limits, null, null, null)).isTrue();
    assertThat(new SupervisorScope.Server().owns(pause, null, null, null)).isFalse();
    assertThat(
            new SupervisorScope.Server()
                .owns(new ExecutionControlCommand.DrainCommand(true), null, null, null))
        .isTrue();
    assertThat(
            new SupervisorScope.Server()
                .owns(new ExecutionControlCommand.RecoverCommand("e"), "s", "w", null))
        .isTrue();
    assertThat(new SupervisorScope.Global().owns(pause, "s", "w", null)).isFalse();
  }

  @Test
  void serverMatchesOnlyCommandsThatBelongToNoRun() {
    final SupervisorScope server = new SupervisorScope.Server();

    assertThat(server.kind()).isEqualTo(SupervisorScope.Kind.SERVER);
    assertThat(server.sessionId()).isNull();
    assertThat(server.workflowId()).isNull();
    assertThat(server.matches(null, null)).isTrue();
    assertThat(server.matches("A", "w")).isFalse();
    assertThat(server.matches("A", null)).isFalse();
    assertThat(server.matches(null, "w")).isFalse();
  }

  @Test
  void serverCoversOnlyAnotherServer() {
    final SupervisorScope server = new SupervisorScope.Server();

    assertThat(server.covers(new SupervisorScope.Server())).isTrue();
    assertThat(server.covers(new SupervisorScope.Global())).isFalse();
    assertThat(server.covers(new SupervisorScope.Session("A"))).isFalse();
  }

  @Test
  void serverIsWrittenAndReadAsServer() {
    assertThat(new SupervisorScope.Server().key()).isEqualTo("server");
    assertThat(SupervisorScope.parse(" server ")).isEqualTo(new SupervisorScope.Server());
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
