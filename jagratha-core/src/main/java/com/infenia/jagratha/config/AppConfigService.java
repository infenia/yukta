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
import jakarta.validation.constraints.NotEmpty;
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
@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.LinguisticNaming", "PMD.TooManyMethods"})
public class AppConfigService {

  private static final String DEFAULT_BASE_DIR = System.getProperty("user.home") + "/.jagratha";
  private static final String DEFAULT_FILE_LOG = DEFAULT_BASE_DIR + "/modified-files";
  private static final String DEFAULT_RES_LOG = DEFAULT_BASE_DIR + "/results";
  private static final long DEFAULT_TIMEOUT = 300L;

  private final Map<String, String> projectPaths = new ConcurrentHashMap<>();
  private final Map<String, Map<String, WorkflowDefinition>> workflowsMap =
      new ConcurrentHashMap<>();
  private final Map<String, String> initiators = new ConcurrentHashMap<>();
  private final Map<String, String> initiatedTimes = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> tagsMap = new ConcurrentHashMap<>();
  private final Map<String, String> descriptions = new ConcurrentHashMap<>();

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
   * Get all workflow definitions for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of workflows
   */
  public Mono<Map<String, WorkflowDefinition>> getWorkflows(@SessionId final String sessionId) {
    final Map<String, WorkflowDefinition> result = workflowsMap.get(sessionId);
    return Mono.just(result != null ? result : Map.of());
  }

  /**
   * Get a specific workflow definition for a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing the workflow definition
   */
  public Mono<WorkflowDefinition> getWorkflow(
      @SessionId final String sessionId, final String workflowId) {
    return getWorkflows(sessionId)
        .flatMap(
            workflows -> {
              final WorkflowDefinition result = workflows.get(workflowId);
              return result != null ? Mono.just(result) : Mono.empty();
            });
  }

  /**
   * Set the workflow definitions for a session.
   *
   * @param sessionId the session identifier
   * @param workflows the map of workflow definitions
   * @return Mono that completes when the workflows are set
   */
  public Mono<Void> setWorkflows(
      @SessionId final String sessionId,
      @NotEmpty(message = "Workflows cannot be empty")
          final Map<String, WorkflowDefinition> workflows) {
    workflowsMap.put(sessionId, workflows);
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
  public Mono<String> getFileLogDir(final String sessionId) {
    return Mono.just(DEFAULT_FILE_LOG);
  }

  /**
   * Get the results log directory for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the log directory
   */
  public Mono<String> getResultLogDir(final String sessionId) {
    return Mono.just(DEFAULT_RES_LOG);
  }

  /**
   * Get the description for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the description
   */
  public Mono<String> getDescription(@SessionId final String sessionId) {
    final String result = descriptions.get(sessionId);
    return Mono.just(result != null ? result : "");
  }

  /**
   * Set the description for a session.
   *
   * @param sessionId the session identifier
   * @param description the description
   * @return Mono that completes when the description is set
   */
  public Mono<Void> setDescription(@SessionId final String sessionId, final String description) {
    if (description != null) {
      descriptions.putIfAbsent(sessionId, description);
    }
    return Mono.empty();
  }

  /**
   * Get the initiator for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the initiator
   */
  public Mono<String> getInitiator(@SessionId final String sessionId) {
    final String result = initiators.get(sessionId);
    return Mono.just(result != null ? result : "");
  }

  /**
   * Set the initiator for a session.
   *
   * @param sessionId the session identifier
   * @param initiator the initiator
   * @return Mono that completes when the initiator is set
   */
  public Mono<Void> setInitiator(@SessionId final String sessionId, final String initiator) {
    if (initiator != null) {
      initiators.putIfAbsent(sessionId, initiator);
    }
    return Mono.empty();
  }

  /**
   * Get the initiated time for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the initiated time
   */
  public Mono<String> getInitiatedTime(@SessionId final String sessionId) {
    final String result = initiatedTimes.get(sessionId);
    return Mono.just(result != null ? result : "");
  }

  /**
   * Set the initiated time for a session.
   *
   * @param sessionId the session identifier
   * @param initiatedTime the initiated time
   * @return Mono that completes when the initiated time is set
   */
  public Mono<Void> setInitiatedTime(
      @SessionId final String sessionId, final String initiatedTime) {
    if (initiatedTime != null) {
      initiatedTimes.putIfAbsent(sessionId, initiatedTime);
    }
    return Mono.empty();
  }

  /**
   * Get the tags for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the tags
   */
  public Mono<Map<String, String>> getTags(@SessionId final String sessionId) {
    final Map<String, String> result = tagsMap.get(sessionId);
    return Mono.just(result != null ? result : Map.of());
  }

  /**
   * Set the tags for a session.
   *
   * @param sessionId the session identifier
   * @param tags the tags
   * @return Mono that completes when the tags are set
   */
  public Mono<Void> setTags(@SessionId final String sessionId, final Map<String, String> tags) {
    if (tags != null) {
      tagsMap.putIfAbsent(sessionId, Map.copyOf(tags));
    }
    return Mono.empty();
  }

  /**
   * Get all configurations for a session as a map.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of configurations
   */
  public Mono<Map<String, Object>> getAllConfigs(@SessionId final String sessionId) {
    return Mono.zip(
        arr -> {
          final Map<String, Object> configs = new java.util.LinkedHashMap<>();
          configs.put("projectPath", arr[0]);
          configs.put("workflows", arr[1]);
          configs.put("executionTimeout", arr[2]);
          configs.put("fileLogDir", arr[3]);
          configs.put("resultLogDir", arr[4]);
          configs.put("initiator", arr[5]);
          configs.put("initiatedTime", arr[6]);
          configs.put("tags", arr[7]);
          configs.put("description", arr[8]);
          return configs;
        },
        getProjectPath(sessionId),
        getWorkflows(sessionId),
        getExecutionTimeout(sessionId),
        getFileLogDir(sessionId),
        getResultLogDir(sessionId),
        getInitiator(sessionId),
        getInitiatedTime(sessionId),
        getTags(sessionId),
        getDescription(sessionId));
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
    active.addAll(initiators.keySet());
    active.addAll(initiatedTimes.keySet());
    active.addAll(tagsMap.keySet());
    active.addAll(descriptions.keySet());
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
