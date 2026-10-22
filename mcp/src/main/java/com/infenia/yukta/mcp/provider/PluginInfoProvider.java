// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.PluginCreationGuide;
import com.infenia.yukta.mcp.dto.PluginDetails;
import com.infenia.yukta.mcp.dto.PluginSummary;
import com.infenia.yukta.mcp.util.PluginTemplateBuilder;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handles plugin registry operations including discovery, details retrieval, and creation guide
 * generation with templates.
 */
@Component
@RequiredArgsConstructor
public class PluginInfoProvider {

  /** Registry for accessing all available plugins. */
  private final PluginRegistry registry;

  /**
   * List all available plugins.
   *
   * @return list of plugin summaries
   */
  public List<PluginSummary> listPlugins() {
    return registry.listPlugins().stream()
        .map(p -> new PluginSummary(p.getType(), p.getCategory()))
        .toList();
  }

  /**
   * Get full details of a specific plugin.
   *
   * @param type the plugin type
   * @return plugin details
   */
  public PluginDetails getPluginDetails(final String type) {
    final Plugin plugin = registry.get(type);
    if (plugin == null) {
      throw new IllegalArgumentException("Plugin not found: " + type);
    }
    return new PluginDetails(
        plugin.getType(),
        plugin.getCategory(),
        plugin.describe().description(),
        plugin.describe().usagePattern(),
        plugin.describe().uiDesign().orElse(null),
        plugin.getOutputPorts());
  }

  /**
   * Get comprehensive guide for creating plugins.
   *
   * @param templateType optional filter ("trigger", "processor", "terminal", "all")
   * @return plugin creation guide
   */
  public PluginCreationGuide getPluginCreationGuide(final String templateType) {
    final String architectureOverview =
        """
        Yukta plugins are Spring beans that implement one of three interfaces from plugin-api: \
        TriggerPlugin (start(config) returns a Flux of the messages that start a run), \
        ProcessorPlugin (process(input, config) turns a Flux of messages into a Flux) or \
        TerminalPlugin (consume(input, config) handles what reaches the end and returns a Mono). \
        Each has a getType() name that workflows use as a node 'type'. PluginRegistry collects \
        every plugin bean and WorkflowOrchestrator runs them as nodes of a Directed Acyclic \
        Graph (DAG). All plugins use reactive streams (Project Reactor) and must not block.\
        """;

    final String integrationExamples =
        """
        Example 1: Annotate the class with @Component; PluginRegistry finds it, there is no \
        manual registration. Example 2: Reference its getType() as a node 'type' in a session \
        (see get_session_creation_instructions). Example 3: Connect it with edges \
        ('source', 'target'). Example 4: Start the workflow with start_workflow. Example 5: \
        Monitor it with get_workflow_status and get_execution_logs.\
        """;

    final String configurationReference =
        """
        A node in a session has 'nodeId' (unique), 'type' (the plugin's getType()) and 'config' \
        (plugin-specific). The config map is passed to every method of the plugin. Describe its \
        fields in getConfigSchema() and check them in validateConfig(config), which should \
        return Mono.error(new IllegalArgumentException(...)) with a descriptive message.\
        """;

    final String validationChecklist =
        """
        1. Pick a getType() that is unique (upper snake case, like the built-in types).
        2. Implement the single abstract method of the interface (start, process or consume).
        3. Validate the config in validateConfig(config).
        4. Handle errors with onErrorResume or similar operators.
        5. Avoid blocking calls; if you must block, override isBlocking() to return true.
        6. Register the plugin as a Spring @Component.
        7. Override describe() so list_plugins can say what the plugin does.
        8. Test the plugin with valid and invalid inputs.
        """;

    final String testingStrategy =
        """
        Use JUnit 5 with Reactor Test for testing plugins.
        Use StepVerifier for reactive stream testing.
        Mock dependencies using Mockito.
        Test both success and failure paths.
        Test configuration validation with invalid inputs.
        Test error handling and edge cases.
        Ensure code coverage above 80% for plugin logic.
        Test integration with WorkflowOrchestrator in integration tests.
        """;

    final String deploymentGuide =
        """
        1. Create plugin module in plugins/<category>/<plugin-name> directory.
        2. Add plugin as dependency in build.gradle of consuming module.
        3. Ensure plugin is on classpath at runtime.
        4. Plugin auto-registration via Spring @Component and stereotype scanning.
        5. Verify plugin appears in listPlugins() MCP tool output.
        6. Create session configuration with workflow nodes using plugin type.
        7. Trigger workflow and monitor execution via MCP tools.
        8. Monitor logs and metrics for plugin performance.
        """;

    return new PluginCreationGuide(
        architectureOverview,
        PluginTemplateBuilder.buildTemplates(templateType),
        integrationExamples,
        configurationReference,
        validationChecklist,
        testingStrategy,
        deploymentGuide);
  }
}
