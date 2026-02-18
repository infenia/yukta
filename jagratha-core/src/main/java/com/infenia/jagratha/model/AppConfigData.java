package com.infenia.jagratha.model;

import java.util.List;
import java.util.Map;

/**
 * Data record for application configuration, used in the service layer.
 *
 * @param sessionId the session identifier
 * @param projectPath the project path
 * @param pluginName the plugin name
 * @param pluginConfig the plugin configuration
 * @param tasks the list of tasks
 * @param workflows the list of workflows
 * @param executionTimeout the execution timeout
 * @param fileLogDir the modified file log directory
 * @param resultLogDir the results log directory
 */
public record AppConfigData(
    String sessionId,
    String projectPath,
    String pluginName,
    Map<String, Object> pluginConfig,
    List<String> tasks,
    List<WorkflowConfig> workflows,
    Long executionTimeout,
    String fileLogDir,
    String resultLogDir) {

  /** Compact constructor to ensure tasks list is immutable and maps are handled. */
  public AppConfigData {
    tasks = tasks != null ? List.copyOf(tasks) : List.of();
    workflows = workflows != null ? List.copyOf(workflows) : List.of();
    pluginConfig = pluginConfig != null ? Map.copyOf(pluginConfig) : Map.of();
  }
}
