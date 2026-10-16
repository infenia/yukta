// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.util.VariableResolver;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The configuration schema the UI renders its form from. */
@NoArgsConstructor
class SessionMemoryConfigSchemaTest {

  @Test
  void modeIsRequiredAndOffersLoadAndAppend() {
    final var schema =
        new SessionMemoryPlugin(new SessionStore(), mock(VariableResolver.class)).getConfigSchema();

    final ConfigField mode = schema.getFirst();
    assertThat(mode.name()).isEqualTo("mode");
    assertThat(mode.required()).isTrue();
    assertThat(mode.options()).containsExactly("load", "append");
    assertThat(schema.stream().map(ConfigField::name))
        .containsExactly("mode", "sessionId", "message", "assistant", "maxTurns");
  }
}
