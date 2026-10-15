// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Keys of the control bus: one node of one run of one workflow. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ControlKeyTest {

  @Test
  void keyWithoutRunIsTheTwoPartKey() {
    assertThat(ControlKey.forNode("wf", null, "n")).isEqualTo("wf\0n");
    assertThat(ControlKey.forNode("wf", "", "n")).isEqualTo("wf\0n");
  }

  @Test
  void keyWithRunHasThreeParts() {
    assertThat(ControlKey.forNode("wf", "run-1", "n")).isEqualTo("wf\0run-1\0n");
  }

  @Test
  void theParts() {
    assertThat(ControlKey.workflowId("wf\0run-1\0n")).isEqualTo("wf");
    assertThat(ControlKey.executionId("wf\0run-1\0n")).isEqualTo("run-1");
    assertThat(ControlKey.nodeId("wf\0run-1\0n")).isEqualTo("n");
    assertThat(ControlKey.workflowId("wf\0n")).isEqualTo("wf");
    assertThat(ControlKey.executionId("wf\0n")).isEmpty();
    assertThat(ControlKey.nodeId("wf\0n")).isEqualTo("n");
  }

  @Test
  void keyIsForNodeOfWorkflowWhateverTheRun() {
    assertThat(ControlKey.isFor("wf\0run-1\0n", "wf", "n")).isTrue();
    assertThat(ControlKey.isFor("wf\0run-2\0n", "wf", "n")).isTrue();
    assertThat(ControlKey.isFor("wf\0n", "wf", "n")).isTrue();
    assertThat(ControlKey.isFor("wf2\0run-1\0n", "wf", "n")).as("other workflow").isFalse();
    assertThat(ControlKey.isFor("wf\0run-1\0n2", "wf", "n")).as("other node").isFalse();
  }

  @Test
  void keyBelongsToWorkflow() {
    assertThat(ControlKey.isIn("wf\0run-1\0n", "wf")).isTrue();
    assertThat(ControlKey.isIn("wf\0n", "wf")).isTrue();
    assertThat(ControlKey.isIn("wf2\0run-1\0n", "wf")).isFalse();
  }
}
