// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessEngineConfigurationTest {

  @Test
  void theTypeIsOfferedWithTheConfiguredList() {
    final var factory =
        new ProcessEngineConfiguration().processSupervisorFactory(List.of("sh", " "), false);
    assertThat(factory.type()).isEqualTo("process");
    assertThat(new ProcessEngineConfiguration().processSupervisorFactory(null, true).type())
        .isEqualTo("process");
  }
}
