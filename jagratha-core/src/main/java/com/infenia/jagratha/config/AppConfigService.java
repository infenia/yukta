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

import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.validation.ProjectPath;
import com.infenia.jagratha.validation.SessionId;
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

  private final Map<String, String> projectPaths = new ConcurrentHashMap<>();
  private final Map<String, WorkflowDefinition> workflowsMap = new ConcurrentHashMap<>();

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
   * Get the workflow definition for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the workflow definition
   */
  public Mono<WorkflowDefinition> getWorkflow(@SessionId final String sessionId) {
    final WorkflowDefinition result = workflowsMap.get(sessionId);
    return result != null ? Mono.just(result) : Mono.empty();
  }

  /**
   * Set the workflow definition for a session.
   *
   * @param sessionId the session identifier
   * @param workflow the workflow definition
   * @return Mono that completes when the workflow is set
   */
  public Mono<Void> setWorkflow(
      @SessionId final String sessionId,
      @NotNull(message = "Workflow definition cannot be null") final WorkflowDefinition workflow) {
    workflowsMap.put(sessionId, workflow);
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
            getWorkflow(sessionId).defaultIfEmpty(new WorkflowDefinition(List.of(), List.of())),
            getExecutionTimeout(sessionId),
            getFileLogDir(sessionId),
            getResultLogDir(sessionId))
        .map(
            tuple -> {
              final Map<String, Object> configs = new java.util.LinkedHashMap<>();
              configs.put("projectPath", tuple.getT1());
              configs.put("workflow", tuple.getT2());
              configs.put("executionTimeout", tuple.getT3());
              configs.put("fileLogDir", tuple.getT4());
              configs.put("resultLogDir", tuple.getT5());
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
