package com.infenia.jagratha.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request object for updating session configuration")
public record ConfigRequest(
    @Schema(description = "The unique session identifier", example = "session-123")
        @NotBlank(message = "Session ID is required")
        @Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid session ID format")
        String sessionId,
    @Schema(description = "The root path of the project to manage", example = "/path/to/project")
        @NotBlank(message = "Project path is required")
        String projectPath,
    @Schema(description = "The name of the build plugin to use (e.g., gradle)", example = "gradle")
        @NotBlank(message = "Plugin name is required")
        String pluginName,
    @Schema(description = "Configuration options for the selected plugin")
        @NotEmpty(message = "Plugin config is required")
        Map<String, Object> pluginConfig,
    @Schema(
            description = "List of tasks to execute (e.g., spotlessApply, build)",
            example = "[\"spotlessApply\", \"build\"]")
        @NotEmpty(message = "Tasks list is required")
        List<String> tasks,
    @Schema(description = "Orchestration workflows for build tasks and AI feedback")
        @NotEmpty(message = "Workflows list is required")
        @Valid
        List<WorkflowConfig> workflows,
    @Schema(description = "Maximum execution time in seconds", example = "300")
        Long executionTimeout,
    @Schema(description = "Directory for modified file logs", example = "/path/to/logs/modified")
        String modifiedFile,
    @Schema(description = "Directory for task results logs", example = "/path/to/logs/results")
        String results) {

  /** Compact constructor to ensure tasks list is immutable and maps are handled. */
  public ConfigRequest {
    tasks = tasks != null ? List.copyOf(tasks) : List.of();
    workflows = workflows != null ? List.copyOf(workflows) : List.of();
    pluginConfig = pluginConfig != null ? Map.copyOf(pluginConfig) : Map.of();
  }
}
