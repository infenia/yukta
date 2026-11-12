// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginTier;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every registered plugin has its tier and restricted flag listed here, so a new plugin fails this
 * test until someone decides how generic and how risky it is.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@NoArgsConstructor
class PluginTierAcceptanceTest {

  private static final Map<String, PluginTier> TIERS = tiers();

  /** Types that reach outside the engine: processes, files, network, databases. */
  private static final Set<String> RESTRICTED =
      Set.of(
          "PROCESS_EXECUTOR",
          "SQL_QUERY",
          "FILE_READ",
          "FILE_WRITE",
          "HTTP_REQUEST",
          "MCP_LIST_TOOLS",
          "MCP_TOOL_CALL",
          "NOTIFY_TERMINAL");

  @Autowired private PluginRegistry registry;

  @Test
  void everyRegisteredPlugin_declaresItsTierAndRestriction() {
    final Map<String, PluginTier> actual =
        registry.listPlugins().stream().collect(Collectors.toMap(Plugin::getType, Plugin::getTier));
    assertThat(TIERS).as("tier by type").containsAllEntriesOf(actual);
    assertThat(
            registry.listPlugins().stream()
                .filter(Plugin::isRestricted)
                .map(Plugin::getType)
                .collect(Collectors.toSet()))
        .as("restricted types")
        .isSubsetOf(RESTRICTED);
    assertThat(
            registry.listPlugins().stream()
                .filter(p -> RESTRICTED.contains(p.getType()))
                .allMatch(Plugin::isRestricted))
        .as("every listed restricted type is flagged")
        .isTrue();
  }

  private static Map<String, PluginTier> tiers() {
    final var specific =
        Set.of(
            "LLM_CALL",
            "SESSION_MEMORY",
            "MCP_LIST_TOOLS",
            "MCP_TOOL_CALL",
            "NOTIFY_TERMINAL",
            "CLASSIFY",
            "GATE",
            "SCORE",
            "ASK",
            "ANOMALY",
            "DEDUPE");
    final var extended =
        Set.of(
            "PROCESS_EXECUTOR", "SQL_QUERY", "FILE_READ", "FILE_WRITE", "HTTP_REQUEST", "KV_STORE");
    final var core =
        Set.of(
            "AGGREGATE",
            "API_TRIGGER",
            "ASK_INPUT",
            "BRANCH",
            "CONFIG_VARIABLE_SOURCE",
            "CONSOLE_TERMINAL",
            "CONSTANT_SOURCE",
            "CONTENT_FILTER",
            "DELAY",
            "FILTER",
            "LOOP_PREDICATE",
            "LOOP_STREAM",
            "MAPPER",
            "OUTCOME_TERMINAL",
            "RECIPIENT_LIST",
            "SCHEDULE_TRIGGER",
            "SPLITTER",
            "SUB_WORKFLOW",
            "SUPERVISOR_DECISION",
            "SUPERVISOR_SIGNAL",
            "VALIDATE",
            "WAIT_FOR_SIGNAL",
            "WEBHOOK_TRIGGER");
    final var map = new java.util.HashMap<String, PluginTier>();
    specific.forEach(t -> map.put(t, PluginTier.SPECIFIC));
    extended.forEach(t -> map.put(t, PluginTier.EXTENDED));
    core.forEach(t -> map.put(t, PluginTier.CORE));
    return Map.copyOf(map);
  }
}
