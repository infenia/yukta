package com.infenia.jagratha.model;

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
    String sessionId,
    String projectPath,
    List<PluginRegistration> plugins,
    List<WorkflowConfig> workflows) {

  /** Compact constructor to ensure lists are immutable. */
  public AppConfigData {
    plugins = plugins != null ? List.copyOf(plugins) : List.of();
    workflows = workflows != null ? List.copyOf(workflows) : List.of();
  }
}
