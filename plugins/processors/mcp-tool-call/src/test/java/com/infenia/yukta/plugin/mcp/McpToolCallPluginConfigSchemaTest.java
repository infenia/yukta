// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
@NoArgsConstructor
class McpToolCallPluginConfigSchemaTest {

  @Test
  void schemaPinsFieldNamesAndRequired() {
    final var plugin =
        new McpToolCallPlugin(
            new McpClientGateway(WebClient.builder(), new ObjectMapper()),
            null,
            Mockito.mock(ObjectProvider.class),
            Mockito.mock(ObjectProvider.class));
    final List<ConfigField> schema = plugin.getConfigSchema();
    assertThat(schema.stream().map(ConfigField::name))
        .containsExactly(
            "url",
            "tool",
            "arguments",
            "headers",
            "budget",
            "allowedTools",
            "requireApproval",
            "approvalSignal",
            "approvalTimeoutSeconds",
            "authToken",
            "timeout",
            "resultKey",
            "failureMode",
            "routeByStatus",
            "concurrency");
    assertThat(schema.stream().filter(ConfigField::required).map(ConfigField::name))
        .containsExactly("url", "tool");
  }
}
