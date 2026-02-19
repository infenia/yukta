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
