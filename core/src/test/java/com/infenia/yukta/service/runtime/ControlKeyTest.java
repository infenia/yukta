// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Keys of the control bus: one node of one run of one workflow. */
@NoArgsConstructor
class ControlKeyTest {

  @Test
  void keyWithoutRunIsTheTwoPartKey() {
    assertThat(ControlKey.forNode("wf", null, "n")).isEqualTo(new ControlKey("wf", null, "n"));
    assertThat(ControlKey.forNode("wf", "", "n")).isEqualTo(new ControlKey("wf", null, "n"));
  }

  @Test
  void keyWithRunKeepsItsExecution() {
    assertThat(ControlKey.forNode("wf", "run-1", "n"))
        .isEqualTo(new ControlKey("wf", "run-1", "n"));
    // a node id that holds the old separator can no longer collide with another key
    assertThat(ControlKey.forNode("wf", null, "a\0b"))
        .isNotEqualTo(ControlKey.forNode("wf\0a", null, "b"));
  }
}
