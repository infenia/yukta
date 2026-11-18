// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.DedicatedServer;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.InJvmOnly;
import com.infenia.yukta.security.ApiRole;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The MCP endpoint limits costly calls ({@code start_workflow}) per caller and minute, lower than
 * its general limit ({@code yukta.mcp.heavy-rate-limit-per-minute}). It needs a low limit, so it
 * runs on a server of its own. The refused call is answered 429 with Retry-After (not a 5xx).
 */
@E2eTest
@InJvmOnly
@NoArgsConstructor
class RateLimitE2eTest {

  private static final String START_CALL =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"start_workflow\","
          + "\"arguments\":{\"sessionId\":\"e2e-nope\",\"workflowId\":\"w\"}}}";

  private static DedicatedServer server;

  @BeforeAll
  static void start() {
    server = DedicatedServer.start(true, "yukta.mcp.heavy-rate-limit-per-minute=2");
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  private static Reply startWorkflow() {
    final Api api = Api.toServer(server.baseUrl(), server.key(ApiRole.ADMIN));
    return Api.reply(
        api.client()
            .post()
            .uri("/mcp")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .bodyValue(START_CALL)
            .exchange());
  }

  @Test
  void costlyCallsOverTheLimitAreRefusedWith429() {
    assertThat(startWorkflow().status()).isEqualTo(200);
    assertThat(startWorkflow().status()).isEqualTo(200);

    final Reply third = startWorkflow();

    assertThat(third.status()).as(third.body()).isEqualTo(429);
    assertThat(third.body()).doesNotContain("Exception", "at com.");
  }
}
