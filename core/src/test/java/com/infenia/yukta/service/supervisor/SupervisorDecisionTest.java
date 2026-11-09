// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SupervisorDecisionTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  @Test
  void decisionWithoutCauseKeyOrNodeKeepsItsFieldsAndLeavesTheRestEmpty() {
    final SupervisorDecision decision =
        new SupervisorDecision(NOW, "s1", "rejected", "StopWorkflowCommand", "e1", "no");

    assertThat(decision.time()).isEqualTo(NOW);
    assertThat(decision.supervisorId()).isEqualTo("s1");
    assertThat(decision.outcome()).isEqualTo("rejected");
    assertThat(decision.command()).isEqualTo("StopWorkflowCommand");
    assertThat(decision.executionId()).isEqualTo("e1");
    assertThat(decision.reason()).isEqualTo("no");
    assertThat(decision.nodeId()).isNull();
    assertThat(decision.cause()).isNull();
    assertThat(decision.key()).isNull();
    assertThat(decision.because()).isNull();
  }

  @Test
  void decisionWithTheFullShapeKeepsEveryField() {
    final SupervisorDecision decision =
        new SupervisorDecision(
            NOW,
            "s1",
            "applied",
            "PauseWorkflowCommand",
            "e1",
            null,
            "n1",
            "event",
            "k#1",
            "because");

    assertThat(decision.reason()).isNull();
    assertThat(decision.nodeId()).isEqualTo("n1");
    assertThat(decision.cause()).isEqualTo("event");
    assertThat(decision.key()).isEqualTo("k#1");
    assertThat(decision.because()).isEqualTo("because");
  }
}
