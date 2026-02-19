package com.infenia.jagratha.model;

import com.infenia.jagratha.validation.ProjectPath;
import com.infenia.jagratha.validation.SessionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
    @SessionId String sessionId,
    @ProjectPath String projectPath,
    @NotEmpty(message = "Plugins list cannot be empty") @Valid List<PluginRegistration> plugins,
    @NotEmpty(message = "Workflows list cannot be empty") @Valid List<WorkflowConfig> workflows) {

  /** Compact constructor to ensure lists are immutable. */
  public AppConfigData {
    plugins = plugins != null ? List.copyOf(plugins) : List.of();
    workflows = workflows != null ? List.copyOf(workflows) : List.of();
  }
}
