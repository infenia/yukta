// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.security.ApiKeys;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Tests for the behaviour described by {@link McpToolAccess}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class McpToolAccessTest {

  private static ApiKeys.Match key(final String role, final String sessions) {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].key", "k")
            .withProperty("yukta.api-keys[0].role", role)
            // null: every session, written out as a non-admin key must
            .withProperty("yukta.api-keys[0].sessions", sessions == null ? "*" : sessions);
    return ApiKeys.read(env, null).find("k");
  }

  @Test
  void listingTheApprovalsOfAllSessionsIsNotForOneKeyLimitedToSomeSessions() {
    // no sessionId means all sessions: only a key without a session limit may ask that
    assertThat(McpToolAccess.permitted(key("VIEWER", null), "list_pending_approvals", null))
        .isTrue();
    assertThat(McpToolAccess.permitted(key("VIEWER", "a"), "list_pending_approvals", null))
        .isFalse();
    assertThat(McpToolAccess.permitted(key("VIEWER", "a"), "list_pending_approvals", "a")).isTrue();
  }

  @Test
  void rolesDecideWhichToolsMayBeCalled() {
    assertThat(McpToolAccess.permitted(key("VIEWER", null), "list_sessions", null)).isTrue();
    assertThat(McpToolAccess.permitted(key("VIEWER", null), "list_pending_approvals", "a"))
        .isTrue();
    assertThat(McpToolAccess.permitted(key("VIEWER", null), "diff_workflow_versions", "a"))
        .isTrue();
    assertThat(McpToolAccess.permitted(key("VIEWER", null), "rollback_workflow", "a")).isFalse();
    assertThat(McpToolAccess.permitted(key("OPERATOR", null), "rollback_workflow", "a")).isTrue();
    assertThat(McpToolAccess.permitted(key("VIEWER", null), "signal_node", "a")).isFalse();
    for (final String control :
        List.of("control_node", "control_workflow", "set_node_breakpoint")) {
      assertThat(McpToolAccess.permitted(key("VIEWER", null), control, "a")).isFalse();
      assertThat(McpToolAccess.permitted(key("OPERATOR", null), control, "a")).isTrue();
    }
    assertThat(McpToolAccess.permitted(key("VIEWER", null), "get_execution_tree", "a")).isTrue();
    assertThat(McpToolAccess.permitted(key("OPERATOR", null), "signal_node", "a")).isTrue();
    assertThat(McpToolAccess.permitted(key("OPERATOR", null), "create_session", "a")).isFalse();
    assertThat(McpToolAccess.permitted(key("OPERATOR", null), "unknown_tool", "a")).isFalse();
    assertThat(McpToolAccess.permitted(key("ADMIN", null), "create_session", null)).isTrue();
  }

  @Test
  void sessionKeysNeedTheirOwnSessionUnlessTheToolNamesNone() {
    final var team = key("ADMIN", "a");

    assertThat(McpToolAccess.permitted(team, "start_workflow", "a")).isTrue();
    assertThat(McpToolAccess.permitted(team, "start_workflow", "b")).isFalse();
    assertThat(McpToolAccess.permitted(team, "start_workflow", null)).isFalse();
    assertThat(McpToolAccess.permitted(team, "list_plugins", null)).isTrue();
  }

  @Test
  void bodiesAreChecked() {
    final String json =
        "{\"method\":\"tools/call\",\"params\":{\"name\":\"start_workflow\","
            + "\"arguments\":{\"sessionId\":\"a\"}}}";
    final byte[] ok = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(McpToolAccess.permitted(key("OPERATOR", null), ok)).isTrue();
  }

  private static byte[] read(final String uri) {
    return ("{\"method\":\"resources/read\",\"params\":{\"uri\":\"" + uri + "\"}}")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void sessionKeysReadOnlyTheLogsOfTheirOwnSession() {
    final var team = key("VIEWER", "a");

    assertThat(McpToolAccess.permitted(team, read("yukta://sessions/a/executions/e1/logs")))
        .isTrue();
    assertThat(McpToolAccess.permitted(team, read("yukta://sessions/b/executions/e1/logs")))
        .isFalse();
    assertThat(McpToolAccess.permitted(team, read("yukta://sessions/%61/executions/e1/logs")))
        .isFalse();
    assertThat(McpToolAccess.permitted(team, read("yukta://sessions//executions/e1/logs")))
        .isFalse();
    assertThat(McpToolAccess.permitted(team, read("yukta://sessions/a"))).isTrue();
    assertThat(McpToolAccess.permitted(team, read("yukta://sessions/b"))).isFalse();
  }

  @Test
  void resourcesWithoutSessionAreOpenToAnyRoleAndUnknownOnesToUnrestrictedAdmins() {
    assertThat(McpToolAccess.permitted(key("VIEWER", "a"), read("yukta://overview"))).isTrue();
    assertThat(McpToolAccess.permitted(key("VIEWER", "a"), read("yukta://architecture"))).isTrue();
    assertThat(McpToolAccess.permitted(key("VIEWER", "a"), read("yukta://other"))).isFalse();
    assertThat(McpToolAccess.permitted(key("VIEWER", null), read("yukta://other"))).isFalse();
    assertThat(McpToolAccess.permitted(key("ADMIN", "a"), read("yukta://other"))).isFalse();
    assertThat(McpToolAccess.permitted(key("ADMIN", null), read("yukta://other"))).isTrue();
    assertThat(
            McpToolAccess.permitted(
                key("VIEWER", null), read("yukta://sessions/b/executions/e/logs")))
        .isTrue();
  }

  @Test
  void batchWithOneForeignResourceReadIsRefused() {
    final byte[] batch =
        ("[{\"method\":\"resources/read\",\"params\":{\"uri\":\"yukta://overview\"}},"
                + "{\"method\":\"resources/read\",\"params\":{\"uri\":"
                + "\"yukta://sessions/b/executions/e/logs\"}}]")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    assertThat(McpToolAccess.permitted(key("VIEWER", "a"), batch)).isFalse();
  }

  @Test
  void deletingNeedsAdminAndScopedKeyKeepsToItsOwnSessions() {
    final var operator =
        new com.infenia.yukta.security.ApiKeys.Match(
            "o", com.infenia.yukta.security.ApiRole.OPERATOR, java.util.Set.of());
    final var admin =
        new com.infenia.yukta.security.ApiKeys.Match(
            "a", com.infenia.yukta.security.ApiRole.ADMIN, java.util.Set.of());
    final var scoped =
        new com.infenia.yukta.security.ApiKeys.Match(
            "s", com.infenia.yukta.security.ApiRole.ADMIN, java.util.Set.of("mine"));

    for (final String tool : new String[] {"delete_session", "delete_workflow"}) {
      org.assertj.core.api.Assertions.assertThat(McpToolAccess.permitted(operator, tool, "mine"))
          .isFalse();
      org.assertj.core.api.Assertions.assertThat(McpToolAccess.permitted(admin, tool, "any"))
          .isTrue();
      org.assertj.core.api.Assertions.assertThat(McpToolAccess.permitted(scoped, tool, "mine"))
          .isTrue();
      org.assertj.core.api.Assertions.assertThat(McpToolAccess.permitted(scoped, tool, "other"))
          .isFalse();
    }
  }

  @Test
  void heavyCallsCountStartsAndRegexLogReadsOnly() {
    final byte[] batch =
        ("[{\"method\":\"tools/call\",\"params\":{\"name\":\"start_workflow\"}},"
                + "{\"method\":\"tools/call\",\"params\":{\"name\":\"get_execution_logs\","
                + "\"arguments\":{\"filterPattern\":\"x\"}}},"
                + "{\"method\":\"tools/call\",\"params\":{\"name\":\"get_execution_logs\","
                + "\"arguments\":{}}}]")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    assertThat(McpToolAccess.heavyCalls(batch)).isEqualTo(2);
    assertThat(
            McpToolAccess.heavyCalls("not json".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isZero();
  }
}
