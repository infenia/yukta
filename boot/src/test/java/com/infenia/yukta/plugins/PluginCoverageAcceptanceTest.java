// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Fails when a plugin is added without a happy-path test in this package. Add its test to one of
 * the {@code *PluginsAcceptanceTest} classes first, then list its type here.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class PluginCoverageAcceptanceTest {

  // ponytail: a hand-kept list, it cannot tell that a listed type really has a test.
  private static final Set<String> TESTED =
      Set.of(
          "AGGREGATE",
          "ANOMALY",
          "API_TRIGGER",
          "ASK",
          "BRANCH",
          "CLASSIFY",
          "CONSOLE_TERMINAL",
          "CONSTANT_SOURCE",
          "CONTENT_FILTER",
          "DEDUPE",
          "DELAY",
          "FILE_READ",
          "FILE_WRITE",
          "FILTER",
          "GATE",
          "HTTP_REQUEST",
          "KV_STORE",
          "LLM_CALL",
          "LOOP_PREDICATE",
          "LOOP_STREAM",
          "MAPPER",
          "MCP_LIST_TOOLS",
          "MCP_TOOL_CALL",
          "NOTIFY_TERMINAL",
          "OUTCOME_TERMINAL",
          "PATCH_GUARD",
          "PROCESS_EXECUTOR",
          "RECIPIENT_LIST",
          "SCHEDULE_TRIGGER",
          "SCORE",
          "SESSION_MEMORY",
          "SPLITTER",
          "SQL_QUERY",
          "SUB_WORKFLOW",
          "VALIDATE",
          "WAIT_FOR_SIGNAL");

  @Autowired private PluginRegistry registry;

  @Test
  void everyRegisteredPlugin_hasHappyPathTest() {
    assertThat(registry.listPlugins().stream().map(Plugin::getType))
        .as("plugins with no happy-path test in com.infenia.yukta.plugins")
        .isSubsetOf(TESTED);
  }
}
