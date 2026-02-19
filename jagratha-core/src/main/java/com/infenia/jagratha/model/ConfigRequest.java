package com.infenia.jagratha.model;

import com.infenia.jagratha.validation.ProjectPath;
import com.infenia.jagratha.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request object for configuration updates.
 *
 * @param sessionId the session identifier
 * @param projectPath the project path
 * @param plugins the list of registered plugins
 * @param workflows the list of workflows
 */
@Schema(description = "Request object for updating session configuration")
public record ConfigRequest(
    @Schema(description = "The unique session identifier", example = "session-123") @SessionId
        String sessionId,
    @Schema(description = "The root path of the project to manage", example = "/path/to/project")
        @ProjectPath
        String projectPath,
    @Schema(description = "List of plugins to be used in this session")
        @NotEmpty(message = "Plugins list is required")
        @Valid
        List<PluginRegistration> plugins,
    @Schema(description = "Orchestration workflows for build tasks and AI feedback")
        @NotEmpty(message = "Workflows list is required")
        @Valid
        List<WorkflowConfig> workflows) {

  /** Compact constructor to ensure lists are immutable. */
  public ConfigRequest {
    plugins = plugins != null ? List.copyOf(plugins) : List.of();
    workflows = workflows != null ? List.copyOf(workflows) : List.of();
  }
}
