/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.dto.response.PluginCreationGuide;
import com.infenia.yukta.dto.response.PluginDetails;
import com.infenia.yukta.dto.response.PluginSummary;
import com.infenia.yukta.mcp.util.PluginTemplateBuilder;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Default implementation of PluginInfoProvider. Handles plugin registry operations including
 * discovery, details retrieval, and creation guide generation with templates.
 */
@Component
@RequiredArgsConstructor
public class DefaultPluginInfoProvider implements PluginInfoProvider {

  /** Registry for accessing all available plugins. */
  private final PluginRegistry registry;

  @Override
  public List<PluginSummary> listPlugins() {
    return registry.listPlugins().stream()
        .map(p -> new PluginSummary(p.getType(), p.getCategory()))
        .toList();
  }

  @Override
  public PluginDetails getPluginDetails(final String type) {
    final Plugin plugin = registry.get(type);
    if (plugin == null) {
      throw new IllegalArgumentException("Plugin not found: " + type);
    }
    return new PluginDetails(
        plugin.getType(),
        plugin.getCategory(),
        plugin.getDescription(),
        plugin.getUsagePattern(),
        plugin.getUiDesign().orElse(null),
        plugin.getOutputPorts());
  }

  @Override
  public PluginCreationGuide getPluginCreationGuide(final String templateType) {
    final String architectureOverview =
        """
        Yukta plugins follow a reactive, plugin-based architecture where each plugin implements
        one of three interfaces: TriggerPlugin (initiates workflows), ProcessorPlugin
        (transforms data), or TerminalPlugin (finalizes workflows). Plugins are
        registered in the WorkflowRegistry and orchestrated by WorkflowOrchestrator as
        nodes in a Directed Acyclic Graph (DAG). All plugins use reactive streams
        (Project Reactor Mono/Flux) for non-blocking operations.
        """;

    final String integrationExamples =
        """
        Example 1: Register plugin in WorkflowRegistry during Spring initialization.
        Example 2: Define workflow with nodes of your plugin type.
        Example 3: Create edges connecting your plugin to other nodes in the DAG.
        Example 4: Trigger workflow execution with optional payload data.
        Example 5: Monitor execution status and logs via MCP tools.
        """;

    final String configurationReference =
        """
        Each plugin node requires: id (unique identifier), type (registered plugin type),
        config (plugin-specific parameters). Plugin configuration is JSON-serialized and
        passed to the execute method via WorkflowContext. Use @Validated and custom
        validators to enforce configuration requirements. Configuration validation errors
        should throw IllegalArgumentException with descriptive messages.
        """;

    final String validationChecklist =
        """
        1. Ensure plugin type is unique and matches class naming convention.
        2. Implement required execute() method returning Mono/Flux.
        3. Validate all configuration fields in execute method.
        4. Handle errors gracefully using onErrorResume or similar operators.
        5. Avoid blocking operations; use reactive operators exclusively.
        6. Register plugin as Spring @Component with @RequiredArgsConstructor.
        7. Add comprehensive JavaDoc comments to plugin class.
        8. Test plugin with both valid and invalid inputs.
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
