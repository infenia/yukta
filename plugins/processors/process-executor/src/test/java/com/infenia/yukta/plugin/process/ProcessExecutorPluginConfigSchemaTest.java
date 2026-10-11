// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.util.VariableResolver;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The configuration schema the UI renders its form from. */
@NoArgsConstructor
class ProcessExecutorPluginConfigSchemaTest {

  @Test
  void declaresConfigSchema() {
    final var schema =
        new ProcessExecutorPlugin(mock(ProcessExecutorGateway.class), mock(VariableResolver.class))
            .getConfigSchema();
    assertTrue(
        schema.stream().anyMatch(f -> f.required() && "command".equals(f.name())),
        "command must be a required field");
  }
}
