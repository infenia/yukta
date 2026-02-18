package com.infenia.jagratha.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
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
 * @param workflows the list of workflows
 * @param executionTimeout the execution timeout
 * @param modifiedFile the modified file log directory
 * @param results the results log directory
 */
public record ConfigRequest(
    @NotBlank(message = "Session ID is required")
        @Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid session ID format")
        String sessionId,
    @NotBlank(message = "Project path is required") String projectPath,
    @NotBlank(message = "Plugin name is required") String pluginName,
    @NotEmpty(message = "Plugin config is required") Map<String, Object> pluginConfig,
    @NotEmpty(message = "Tasks list is required") List<String> tasks,
    @NotEmpty(message = "Workflows list is required") @Valid List<WorkflowConfig> workflows,
    Long executionTimeout,
    String modifiedFile,
    String results) {

  /** Compact constructor to ensure tasks list is immutable and maps are handled. */
  public ConfigRequest {
    tasks = tasks != null ? List.copyOf(tasks) : List.of();
    workflows = workflows != null ? List.copyOf(workflows) : List.of();
    pluginConfig = pluginConfig != null ? Map.copyOf(pluginConfig) : Map.of();
  }
}
