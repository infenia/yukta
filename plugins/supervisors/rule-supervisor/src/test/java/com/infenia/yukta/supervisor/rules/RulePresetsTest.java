// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.SupervisorPreset;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class RulePresetsTest {

  @Test
  void everyPresetIsARuleDocumentThatLoads() {
    assertThat(RulePresets.ALL)
        .extracting(SupervisorPreset::name)
        .containsExactly("failure-guard", "approval-gate", "too-many-running");
    for (final SupervisorPreset preset : RulePresets.ALL) {
      final RuleSet rules = RuleSupervisor.read(preset.settings());
      assertThat(rules.rules()).as(preset.name()).hasSize(1);
      assertThat(new RuleSupervisor(rules, Clock.systemUTC())).isNotNull();
    }
  }

  @Test
  void theFactoryOffersThem() {
    assertThat(new RuleSupervisorFactory().presets()).isSameAs(RulePresets.ALL);
  }
}
