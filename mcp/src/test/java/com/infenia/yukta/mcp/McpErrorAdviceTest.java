// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class McpErrorAdviceTest {

  private final McpErrorAdvice advice = new McpErrorAdvice();

  @Test
  void sdkErrorKeepsItsCodeAndMessage() {
    final var response =
        advice.handle(
            new McpError(
                new McpSchema.JSONRPCResponse.JSONRPCError(-32601, "No such method", null)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .containsEntry("error", Map.of("code", -32601, "message", "No such method"));
  }
}
