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
package com.infenia.jagratha.config;

import com.infenia.jagratha.model.PluginRegistration;
import com.infenia.jagratha.model.WorkflowConfig;
import com.infenia.jagratha.validation.ProjectPath;
import com.infenia.jagratha.validation.SessionId;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service for managing app configuration with runtime overrides. All configuration is session-based
 * and updated via API.
 */
@Service
@Validated
@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.LinguisticNaming"})
public class AppConfigService {

  private static final String DEFAULT_BASE_DIR = System.getProperty("user.home") + "/.jagratha";
  private static final String DEFAULT_FILE_LOG = DEFAULT_BASE_DIR + "/modified-files";
  private static final String DEFAULT_RES_LOG = DEFAULT_BASE_DIR + "/results";
  private static final long DEFAULT_TIMEOUT = 300L;
  private static final List<String> DEFAULT_TASKS =
      List.of("spotlessApply", "spotlessCheck", "checkstyleMain", "test");

  private final Map<String, String> projectPaths = new ConcurrentHashMap<>();
  private final Map<String, List<PluginRegistration>> pluginsMap = new ConcurrentHashMap<>();
  private final Map<String, List<WorkflowConfig>> workflowsMap = new ConcurrentHashMap<>();

  /** Public constructor. */
  public AppConfigService() {
    super();
  }

  /**
   * Get the external project path for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the project path
   */
  public Mono<String> getProjectPath(@SessionId final String sessionId) {
    final String result = projectPaths.get(sessionId);
    return Mono.just(result != null ? result : "");
  }

  /**
   * Set the external project path override for a session.
   *
   * @param sessionId the session identifier
   * @param path the project path
   * @return Mono that completes when the path is set
   */
  public Mono<Void> setProjectPath(
      @SessionId final String sessionId, @ProjectPath final String path) {
    projectPaths.put(sessionId, path);
    return Mono.empty();
  }

  /**
   * Get all registered plugins for a session.
   *
   * @param sessionId the session identifier
   * @return Flux of plugins
   */
  public Flux<PluginRegistration> getPlugins(@SessionId final String sessionId) {
    final List<PluginRegistration> result = pluginsMap.get(sessionId);
    return Flux.fromIterable(result != null ? result : List.of());
  }

  /**
   * Set the registered plugins for a session.
   *
   * @param sessionId the session identifier
   * @param plugins the list of plugins
   * @return Mono that completes when plugins are set
   */
  public Mono<Void> setPlugins(
      @SessionId final String sessionId,
      @NotEmpty(message = "Plugins list cannot be empty") final List<PluginRegistration> plugins) {
    pluginsMap.put(sessionId, List.copyOf(plugins));
    return Mono.empty();
  }

  /**
   * Get the plugin name for a session. For now, returns the first plugin's name.
   *
   * @param sessionId the session identifier
   * @return Mono containing the plugin name
   */
  public Mono<String> getPluginName(@SessionId final String sessionId) {
    return getPlugins(sessionId).next().map(PluginRegistration::name).defaultIfEmpty("");
  }

  /**
   * Get the plugin configuration for a session. For now, returns the first plugin's configuration.
   *
   * @param sessionId the session identifier
   * @return Mono containing the plugin configuration map
   */
  public Mono<Map<String, Object>> getPluginConfig(@SessionId final String sessionId) {
    return getPlugins(sessionId)
        .next()
        .map(PluginRegistration::pluginConfig)
        .defaultIfEmpty(Map.of());
  }

  /**
   * Get the list of tasks for a session. Defaults to a standard list if not set.
   *
   * @param sessionId the session identifier
   * @return Flux of tasks
   */
  public Flux<String> getTasks(@SessionId final String sessionId) {
    return Flux.fromIterable(DEFAULT_TASKS);
  }

  /**
   * Get the list of workflows for a session.
   *
   * @param sessionId the session identifier
   * @return Flux of workflows
   */
  public Flux<WorkflowConfig> getWorkflows(@SessionId final String sessionId) {
    final List<WorkflowConfig> override = workflowsMap.get(sessionId);
    return Flux.fromIterable(override != null ? override : List.of());
  }

  /**
   * Set the workflows override for a session.
   *
   * @param sessionId the session identifier
   * @param workflowsList the list of workflows
   * @return Mono that completes when workflows are set
   */
  public Mono<Void> setWorkflows(
      @SessionId final String sessionId,
      @NotNull(message = "Workflows list cannot be null")
          final List<WorkflowConfig> workflowsList) {
    if (workflowsList.isEmpty()) {
      workflowsMap.remove(sessionId);
    } else {
      workflowsMap.put(sessionId, List.copyOf(workflowsList));
    }
    return Mono.empty();
  }

  /**
   * Get the execution timeout in seconds for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the timeout
   */
  public Mono<Long> getExecutionTimeout(@SessionId final String sessionId) {
    return Mono.just(DEFAULT_TIMEOUT);
  }

  /**
   * Get the modified files log directory for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the log directory
   */
  public Mono<String> getFileLogDir(@SessionId final String sessionId) {
    return Mono.just(DEFAULT_FILE_LOG);
  }

  /**
   * Get the results log directory for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the log directory
   */
  public Mono<String> getResultLogDir(@SessionId final String sessionId) {
    return Mono.just(DEFAULT_RES_LOG);
  }

  /**
   * Get all configurations for a session as a map.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of configurations
   */
  public Mono<Map<String, Object>> getAllConfigs(@SessionId final String sessionId) {
    return Mono.zip(
            getProjectPath(sessionId),
            getPlugins(sessionId).collectList(),
            getWorkflows(sessionId).collectList(),
            getExecutionTimeout(sessionId),
            getFileLogDir(sessionId),
            getResultLogDir(sessionId))
        .map(
            tuple -> {
              final Map<String, Object> configs = new java.util.LinkedHashMap<>();
              configs.put("projectPath", tuple.getT1());
              configs.put("plugins", tuple.getT2());
              configs.put("workflows", tuple.getT3());
              configs.put("executionTimeout", tuple.getT4());
              configs.put("fileLogDir", tuple.getT5());
              configs.put("resultLogDir", tuple.getT6());
              return configs;
            });
  }

  /**
   * Get all active session IDs currently in memory.
   *
   * @return Flux of session IDs
   */
  public Flux<String> getActiveSessionIds() {
    final java.util.Set<String> active = new java.util.HashSet<>();
    active.addAll(projectPaths.keySet());
    active.addAll(pluginsMap.keySet());
    active.addAll(workflowsMap.keySet());
    return Flux.fromIterable(active);
  }

  /**
   * Check if a session is active in memory.
   *
   * @param sessionId the session identifier
   * @return Mono containing true if active
   */
  public Mono<Boolean> isActive(@SessionId final String sessionId) {
    return getActiveSessionIds().collectList().map(list -> list.contains(sessionId));
  }
}
