package com.infenia.jagratha.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * Data record for application configuration, used in the service layer.
 *
 * @param sessionId the session identifier
 * @param projectPath the project path
 * @param plugins the list of registered plugins
 * @param workflows the list of workflows
 */
public record AppConfigData(
    @NotBlank(message = "Session ID is required")
        @Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid session ID format")
        String sessionId,
    @NotBlank(message = "Project path is required") String projectPath,
    @NotEmpty(message = "Plugins list cannot be empty") @Valid List<PluginRegistration> plugins,
    @NotEmpty(message = "Workflows list cannot be empty") @Valid List<WorkflowConfig> workflows) {

  /** Compact constructor to ensure lists are immutable. */
  public AppConfigData {
    plugins = plugins != null ? List.copyOf(plugins) : List.of();
    workflows = workflows != null ? List.copyOf(workflows) : List.of();
  }
}
