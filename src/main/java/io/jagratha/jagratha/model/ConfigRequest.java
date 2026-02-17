package io.jagratha.jagratha.model;

import java.util.List;
import java.util.Map;

/**
 * Request object for configuration updates.
 *
 * @param sessionId the session identifier (optional)
 * @param projectPath the project path
 * @param pluginName the plugin name
 * @param pluginConfig the plugin configuration
 * @param tasks the list of tasks
 * @param executionTimeout the execution timeout
 * @param modifiedFile the modified file log directory
 * @param results the results log directory
 */
public record ConfigRequest(
    String sessionId,
    String projectPath,
    String pluginName,
    Map<String, Object> pluginConfig,
    List<String> tasks,
    Long executionTimeout,
    String modifiedFile,
    String results) {

  /** Compact constructor to ensure tasks list is immutable and maps are handled. */
  public ConfigRequest {
    tasks = tasks != null ? List.copyOf(tasks) : List.of();
    pluginConfig = pluginConfig != null ? Map.copyOf(pluginConfig) : Map.of();
  }
}
