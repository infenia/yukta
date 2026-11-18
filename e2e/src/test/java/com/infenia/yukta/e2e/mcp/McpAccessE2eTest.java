// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.DedicatedServer;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.InJvmOnly;
import com.infenia.yukta.e2e.support.YuktaServer;
import com.infenia.yukta.security.ApiRole;
import java.io.IOException;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Who may call which tool. The role of each tool is the one {@code docs/reference/mcp-tools.mdx}
 * names (kept equal to {@code McpToolAccess} by its drift test): the lowest role is let through,
 * the role below is refused with 403 before the tool runs. A session-limited key reaches only its
 * own sessions.
 */
@E2eTest
@NoArgsConstructor
class McpAccessE2eTest {

  private static String call(final String tool, final String arguments) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
        + tool
        + "\",\"arguments\":"
        + arguments
        + "}}";
  }

  private static Reply post(final String key, final String body) {
    return Api.reply(
        Api.toServer(YuktaServer.baseUrl(), key)
            .client()
            .post()
            .uri("/mcp")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .bodyValue(body)
            .exchange());
  }

  /* default */ static Stream<Arguments> tools() throws IOException {
    return McpDocs.roles().entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
  }

  @ParameterizedTest(name = "{0} needs {1}")
  @MethodSource("tools")
  @InJvmOnly
  void theLowestRoleIsLetInAndTheOneBelowIsRefused(final String tool, final ApiRole lowest) {
    final Reply allowed = post(YuktaServer.key(lowest), call(tool, "{}"));

    assertThat(allowed.status()).as("%s as %s: %s", tool, lowest, allowed.body()).isEqualTo(200);
    assertThat(allowed.body()).as(tool).contains("\"result\"");
    if (lowest != ApiRole.VIEWER) {
      final ApiRole below = ApiRole.values()[lowest.ordinal() - 1];
      assertThat(post(YuktaServer.key(below), call(tool, "{}")).status())
          .as("%s as %s", tool, below)
          .isEqualTo(403);
    }
  }

  @Test
  @InJvmOnly
  void sessionLimitedKeyReachesOnlyItsOwnSession() {
    final String key = YuktaServer.scopedKey();
    final String own = DedicatedServer.SCOPED_SESSION;

    assertThat(post(key, call("get_session_details", "{\"sessionId\":\"" + own + "\"}")).status())
        .as("its own session")
        .isEqualTo(200);
    assertThat(post(key, call("get_session_details", "{\"sessionId\":\"e2e-scope-b\"}")).status())
        .as("another session")
        .isEqualTo(403);
    assertThat(post(key, call("get_workflow_history", "{}")).status())
        .as("a session tool with no session")
        .isEqualTo(403);
    assertThat(post(key, call("whoami", "{}")).status())
        .as("a tool with no session")
        .isEqualTo(200);
  }

  @Test
  void requestWithoutKeyIsRefused() {
    assertThat(post(null, call("whoami", "{}")).status()).isEqualTo(401);
  }
}
