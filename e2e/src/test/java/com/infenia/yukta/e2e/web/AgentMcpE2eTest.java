// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.YuktaServer;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** {@code POST /api/agent/v1/mcp}: the agent API as MCP tools, with the supervisor credential. */
@E2eTest
@NoArgsConstructor
class AgentMcpE2eTest {

  private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

  @Test
  void theToolsOfTheAgentApiAreListed(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);

    final Reply reply = fixture.agent().call("POST", "/api/agent/v1/mcp", LIST);

    assertThat(reply.status()).as(reply.body()).isEqualTo(200);
    assertThat(reply.body()).contains("get_snapshot", "next_signals");
  }

  @Test
  void toolCallIsAnswered(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);

    final Reply reply =
        fixture
            .agent()
            .call(
                "POST",
                "/api/agent/v1/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"get_snapshot\",\"arguments\":{}}}");

    assertThat(reply.status()).as(reply.body()).isEqualTo(200);
    assertThat(reply.body()).contains("\"result\"");
  }

  @Test
  void withoutCredentialItIs401() {
    final Reply reply =
        Api.toServer(YuktaServer.baseUrl(), null).call("POST", "/api/agent/v1/mcp", LIST);

    assertThat(reply.status()).isEqualTo(401);
  }
}
