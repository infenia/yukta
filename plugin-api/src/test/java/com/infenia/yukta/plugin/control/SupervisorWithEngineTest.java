// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupervisorWithEngineTest {

  @Test
  void withEngineChangesTheTypeAndSettingsAndKeepsTheRest() {
    final Supervisor old =
        new Supervisor(
            "s1", "rule-supervisor", Map.of("a", 1), true, "why", List.of("p"), Map.of());

    final Supervisor adopted = old.withEngine("rules", Map.of("b", 2));

    assertThat(adopted.type()).isEqualTo("rules");
    assertThat(adopted.settings()).isEqualTo(Map.of("b", 2));
    assertThat(adopted.id()).isEqualTo("s1");
    assertThat(adopted.dryRun()).isTrue();
    assertThat(adopted.description()).isEqualTo("why");
    assertThat(adopted.parents()).containsExactly("p");
  }
}
