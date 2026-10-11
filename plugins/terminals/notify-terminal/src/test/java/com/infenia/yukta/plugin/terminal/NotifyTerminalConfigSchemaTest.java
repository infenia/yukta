// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.ConfigField;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** The configuration schema the UI renders its form from. */
@NoArgsConstructor
class NotifyTerminalConfigSchemaTest {

  @Test
  void requiresTheWebhookUrl() {
    final var schema = new NotifyTerminal(WebClient.builder()).getConfigSchema();

    assertThat(schema.stream().map(ConfigField::name))
        .containsExactly("url", "text", "timeoutMs", "failureMode");
    assertThat(schema.getFirst().required()).isTrue();
  }
}
