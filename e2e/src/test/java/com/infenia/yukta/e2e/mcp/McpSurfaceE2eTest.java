// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Mcp;
import com.infenia.yukta.security.ApiRole;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The tools, prompts and resources an agent sees, against the table in {@code
 * docs/reference/mcp-tools.mdx}.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops"})
class McpSurfaceE2eTest {

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  @Test
  void theToolsAreTheOnesTheDocumentationNames() throws IOException {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final List<String> names = mcp.tools().stream().map(McpSchema.Tool::name).sorted().toList();

      assertThat(names).containsExactlyInAnyOrderElementsOf(McpDocs.roles().keySet());
      assertThat(names).hasSize(65);
      assertThat(names)
          .doesNotContain("trigger_workflow", "stream_session_logs", "get_workflow_execution_logs");
    }
  }

  @Test
  void everyToolHasDescriptionAndAnInputSchema() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      for (final McpSchema.Tool tool : mcp.tools()) {
        assertThat(tool.description()).as(tool.name()).isNotBlank();
        assertThat(tool.inputSchema()).as(tool.name()).isNotNull();
      }
    }
  }

  @Test
  void theReadOnlyToolsSayTheyAre() throws IOException {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final var hints =
          mcp.tools().stream()
              .filter(t ->
                  t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint()))
              .map(McpSchema.Tool::name)
              .collect(Collectors.toSet());

      assertThat(hints).contains("list_sessions", "get_workflow_status", "whoami");
      assertThat(hints).doesNotContain("start_workflow", "create_session", "delete_session");
    }
  }

  @Test
  void theOutputSchemasAcceptTheFieldsAnEmptyRunLacks() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      for (final McpSchema.Tool tool : mcp.tools()) {
        if (List.of("get_workflow_status", "get_execution_tree", "get_workflow_history")
            .contains(tool.name())) {
          assertThat(tool.outputSchema()).as(tool.name()).isNotNull();
          for (final String field : new String[] {"parentExecutionId", "restartOf", "endTime"}) {
            assertThat(requiredNonNull(JSON.valueToTree(tool.outputSchema()), field))
                .as("%s.%s must accept null", tool.name(), field)
                .isFalse();
          }
        }
      }
    }
  }

  /** Whether some object in the schema has a property that does not allow null. */
  private static boolean requiredNonNull(final JsonNode schema, final String field) {
    final JsonNode property = schema.path("properties").path(field);
    boolean found = !property.isMissingNode() && !property.toString().contains("null");
    for (final JsonNode child : schema) {
      found = found || ((child.isObject() || child.isArray()) && requiredNonNull(child, field));
    }
    return found;
  }

  @Test
  void promptsAreListedAndRendered() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final List<String> names = mcp.prompts().stream().map(McpSchema.Prompt::name).toList();

      assertThat(names).contains("debug-workflow", "create-session-config");
      assertThat(
              mcp.prompt("debug-workflow", Map.of("sessionId", "s", "executionId", "e")).messages())
          .isNotEmpty();
    }
  }

  @Test
  void resourcesAreListedAndRead() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final List<String> uris = mcp.resources().stream().map(McpSchema.Resource::uri).toList();

      assertThat(uris).contains("yukta://overview", "yukta://architecture");
      assertThat(mcp.read("yukta://overview")).isNotBlank();
    }
  }
}
