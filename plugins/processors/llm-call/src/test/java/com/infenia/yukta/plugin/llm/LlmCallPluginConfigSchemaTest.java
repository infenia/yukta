// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.util.VariableResolver;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The configuration schema the UI renders its form from. */
@NoArgsConstructor
class LlmCallPluginConfigSchemaTest {

  @Test
  void declaresConfigSchema() {
    final var schema =
        new LlmCallPlugin(mock(LlmGateway.class), mock(VariableResolver.class)).getConfigSchema();
    assertTrue(
        schema.stream().anyMatch(f -> f.required() && "model".equals(f.name())),
        "model must be a required field");
  }
}
