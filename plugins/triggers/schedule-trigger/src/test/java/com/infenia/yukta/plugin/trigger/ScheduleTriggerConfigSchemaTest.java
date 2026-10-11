// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.ConfigField;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The configuration schema the UI renders its form from. */
@NoArgsConstructor
class ScheduleTriggerConfigSchemaTest {

  @Test
  void offersIntervalOrCronWithoutRequiringEither() {
    final var schema = new ScheduleTrigger().getConfigSchema();

    assertThat(schema.stream().map(ConfigField::name))
        .containsExactly("intervalMs", "cron", "zone", "maxRuns");
    assertThat(schema).noneMatch(ConfigField::required);
  }
}
