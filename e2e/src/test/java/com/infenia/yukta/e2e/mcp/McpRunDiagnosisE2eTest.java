// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Mcp;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.Workflows;
import com.infenia.yukta.security.ApiRole;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An agent finds out why a run failed the cheap way: the digest names the failing test in a few
 * kilobytes, the search counts first and returns only the matching lines, and the whole log costs
 * many times more.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class McpRunDiagnosisE2eTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  private static int size(final McpSchema.CallToolResult result) {
    return Mcp.json(result).toString().getBytes(StandardCharsets.UTF_8).length;
  }

  @Test
  void failedRunIsExplainedByTheDigestAndTheSearch(final Scenarios scenarios) {
    final String session = scenarios.applySession(Workflows.failingTest(Scenarios.unique("diag")));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());
    assertThat(scenarios.awaitTerminal(execution, WAIT).path("status").asString())
        .isEqualTo("FAILURE");
    final Map<String, Object> run = Map.of("sessionId", session, "executionId", execution);

    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final McpSchema.CallToolResult digestResult = mcp.call("get_failure_digest", run);
      final JsonNode digest = Mcp.json(digestResult).path("digest");
      assertThat(digest.path("status").asString()).isEqualTo("FAILURE");
      assertThat(digest.path("failedNodes").get(0).path("nodeId").asString()).isEqualTo("build");
      final JsonNode finding = digest.path("findings").get(0);
      assertThat(finding.path("kind").asString()).isEqualTo("TEST");
      assertThat(finding.path("name").asString()).isEqualTo("com.x.FooTest > adds()");
      assertThat(finding.path("location").asString()).isEqualTo("FooTest.java:27");
      assertThat(digest.path("next").get(0).path("kind").asString()).isEqualTo("NODE_OUTPUT");
      assertThat(size(digestResult)).isLessThan(6_000);

      final JsonNode count =
          Mcp.json(mcp.call("search_logs", Map.of("sessionId", session, "executionId", execution)))
              .path("search");
      assertThat(count.path("lines")).isEmpty();
      assertThat(count.path("nodes").get(0).path("nodeId").asString()).isEqualTo("build");

      final JsonNode lines =
          Mcp.json(
                  mcp.call(
                      "search_logs",
                      Map.of(
                          "sessionId",
                          session,
                          "executionId",
                          execution,
                          "grep",
                          "FAILED",
                          "context",
                          1,
                          "mode",
                          "LINES")))
              .path("search");
      assertThat(lines.path("matches").asLong()).isEqualTo(1);
      assertThat(lines.path("lines")).hasSize(3);

      final McpSchema.CallToolResult whole =
          mcp.call(
              "get_execution_logs",
              Map.of("sessionId", session, "executionId", execution, "tailLines", 5000));
      assertThat(size(whole)).isGreaterThan(3 * size(digestResult));
    }
  }
}
