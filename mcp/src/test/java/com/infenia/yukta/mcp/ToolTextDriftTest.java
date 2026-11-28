// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.mcp.dto.NodeControlAction;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.mock.env.MockEnvironment;

/** Fails when the hand-written tool text no longer matches the tools in the code. */
class ToolTextDriftTest {

  /** The reference page that lists every tool with the role it needs. */
  private static final Path REFERENCE = Path.of("..", "docs", "reference", "mcp-tools.mdx");

  /** A table row of the reference: the tool name and its role. */
  private static final Pattern ROW =
      Pattern.compile("^\\| `([a-z_]+)` \\| (VIEWER|OPERATOR|ADMIN) \\|", Pattern.MULTILINE);

  private static Stream<Method> tools() {
    return Stream.of(AppMcpTools.class, ConversationTools.class, WorkflowTestTools.class)
        .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
        .filter(m -> m.isAnnotationPresent(McpTool.class));
  }

  private static ApiRole lowestRole(final String tool) {
    return Arrays.stream(ApiRole.values())
        .filter(role -> McpToolAccess.permitted(key(role), tool, "s1"))
        .findFirst()
        .orElseThrow();
  }

  private static ApiKeys.Match key(final ApiRole role) {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].key", "k")
            .withProperty("yukta.api-keys[0].role", role.name())
            .withProperty("yukta.api-keys[0].sessions", "*");
    return ApiKeys.read(env).find("k");
  }

  @Test
  void theReferenceListsEveryToolWithTheRoleItNeeds() throws IOException {
    final Map<String, ApiRole> documented = new TreeMap<>();
    final Matcher row = ROW.matcher(Files.readString(REFERENCE));
    while (row.find()) {
      documented.put(row.group(1), ApiRole.valueOf(row.group(2)));
    }
    final Map<String, ApiRole> actual = new TreeMap<>();
    tools()
        .map(m -> m.getAnnotation(McpTool.class).name())
        .forEach(name -> actual.put(name, lowestRole(name)));

    assertThat(documented).as("tools and roles in %s", REFERENCE).isEqualTo(actual);
  }

  @Test
  void everyControlActionIsNamedInItsParameterDescription() {
    final List<Parameter> actions =
        tools()
            .flatMap(m -> Arrays.stream(m.getParameters()))
            .filter(p ->
                p.getType() == WorkflowControlAction.class
                    || p.getType() == NodeControlAction.class)
            .toList();
    assertThat(actions).hasSize(2);
    for (final Parameter p : actions) {
      assertThat(description(p))
          .as(p.getType().getSimpleName())
          .contains(Arrays.stream(p.getType().getEnumConstants()).map(Object::toString).toList());
    }
  }

  private static String description(final Parameter parameter) {
    return parameter.getAnnotation(McpToolParam.class).description();
  }
}
