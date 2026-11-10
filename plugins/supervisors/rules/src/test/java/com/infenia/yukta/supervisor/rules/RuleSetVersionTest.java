// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuleSetVersionTest {

  @Test
  void documentWithoutVersionIsVersionOne() {
    assertThat(RuleSupervisor.read("{\"rules\": []}").schemaVersion()).isNull();
  }

  @Test
  void versionOneIsRead() {
    assertThat(RuleSupervisor.read("{\"schemaVersion\": 1, \"rules\": []}").schemaVersion())
        .isEqualTo(1);
  }

  @Test
  void anotherVersionIsRefused() {
    assertThatThrownBy(() -> RuleSupervisor.read("{\"schemaVersion\": 2, \"rules\": []}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaVersion 2");
  }
}
