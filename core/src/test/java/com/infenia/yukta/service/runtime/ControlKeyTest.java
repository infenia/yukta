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
    assertThat(ControlKey.forNode("wf", null, "n")).isEqualTo("wf\0n");
    assertThat(ControlKey.forNode("wf", "", "n")).isEqualTo("wf\0n");
  }

  @Test
  void keyWithRunHasThreeParts() {
    assertThat(ControlKey.forNode("wf", "run-1", "n")).isEqualTo("wf\0run-1\0n");
  }
}
