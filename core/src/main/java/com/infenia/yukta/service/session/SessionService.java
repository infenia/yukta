// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Service for managing session lifecycle and configuration. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class SessionService {

  /** The session configuration store for persisting session settings. */
  private final SessionConfigStore configService;

  /** The control bus gateway for managing workflow execution control. */
  private final ControlBusGateway controlBus;

  /** The workflow definition store for accessing workflow definitions. */
  private final WorkflowDefinitionStore workflowDefinitionStore;

  /** The prepared workflow cache for invalidating stale compiled workflows. */
  private final PreparedWorkflowCache preparedWorkflowCache;

  /**
   * Apply configuration for a session.
   *
   * <p>This method initializes a new session or updates an existing one by applying all
   * configuration data atomically via the store. Workflow preparation is now decoupled to break
   * circular dependencies.
   *
   * @param data the configuration data to apply
   * @return Mono that completes when the configuration is successfully applied and persisted
   */
  public Mono<Void> applyConfig(@Valid final SessionConfigData data) {
    final Mono<Void> staleWorkflowCleanup =
        Mono.defer(() -> workflowDefinitionStore.findAll(data.sessionId()))
            .flatMapMany(existing -> Flux.fromIterable(existing.keySet()))
            .filter(existingWorkflowId -> !data.workflows().containsKey(existingWorkflowId))
            .flatMap(
                staleWorkflowId ->
                    workflowDefinitionStore
                        .remove(data.sessionId(), staleWorkflowId)
                        .doOnSuccess(
                            _ ->
                                log.atInfo().log(
                                    "Removed stale workflow: {} no longer present in config for"
                                        + " session: {}",
                                    staleWorkflowId,
                                    data.sessionId()))
                        .onErrorResume(
                            err -> {
                              log.atWarn()
                                  .addArgument(staleWorkflowId)
                                  .addArgument(data.sessionId())
                                  .setCause(err)
                                  .log(
                                      "Failed to remove stale workflow: {} for session: {},"
                                          + " continuing");
                              return Mono.empty();
                            }))
            .then();
    final Mono<Void> cacheInvalidation =
        Mono.fromRunnable(
            () -> {
              log.atDebug().log(
                  "Invalidating prepared workflow cache for session: {}", data.sessionId());
              preparedWorkflowCache.invalidateAll(data.sessionId());
            });
    final Mono<Void> workflowCompilation =
        data.workflows().isEmpty()
            ? Mono.<Void>empty()
                .doOnSuccess(
                    _ ->
                        log.atDebug().log(
                            "No workflows to compile for session: {}", data.sessionId()))
            : Flux.fromIterable(data.workflows().values())
                .doOnSubscribe(
                    _ ->
                        log.atInfo().log(
                            "Starting compilation of {} workflows for session: {}",
                            data.workflows().size(),
                            data.sessionId()))
                .flatMap(
                    def ->
                        controlBus
                            .compileAndCacheWorkflow(data.sessionId(), def)
                            .doOnSuccess(
                                _ ->
                                    log.atDebug().log(
                                        "Successfully compiled workflow: {} for session: {}",
                                        def.workflowId(),
                                        data.sessionId()))
                            .doOnError(
                                err ->
                                    log.atError()
                                        .addArgument(def.workflowId())
                                        .addArgument(data.sessionId())
                                        .setCause(err)
                                        .log("Failed to compile workflow: {} for session: {}")))
                .then();
    return configService
        .applySessionConfig(data)
        .doOnSubscribe(
            _ -> log.atInfo().log("Applying configuration for session: {}", data.sessionId()))
        .then(staleWorkflowCleanup)
        .then(cacheInvalidation)
        .then(workflowCompilation)
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "Configuration applied successfully for session: {}", data.sessionId()))
        .doOnError(
            err ->
                log.atError()
                    .addArgument(data.sessionId())
                    .setCause(err)
                    .log("Failed to apply configuration for session: {}"));
  }

  /**
   * Get all available session IDs.
   *
   * <p>Retrieves all session identifiers that have configurations stored in the system, regardless
   * of whether they are currently active or not.
   *
   * @return Flux of session IDs
   */
  public Flux<String> getSessionIds() {
    return configService
        .getSessionIds()
        .doOnSubscribe(_ -> log.atDebug().log("Retrieving all session IDs"))
        .doOnNext(sessionId -> log.atTrace().log("Found session: {}", sessionId))
        .doOnComplete(() -> log.atDebug().log("Session ID retrieval completed"))
        .doOnError(err -> log.atError().setCause(err).log("Failed to retrieve session IDs"));
  }

  /**
   * Get configuration for a session.
   *
   * <p>The store implementation (file or in-memory) handles retrieval from its configured backend.
   * Returns the complete session configuration including metadata and all workflows.
   *
   * @param sessionId the session identifier
   * @return Mono containing the session configuration response
   */
  public Mono<SessionConfigResponse> getSessionConfig(@SessionId final String sessionId) {
    return configService
        .getAllConfigs(sessionId)
        .doOnSubscribe(_ -> log.atDebug().log("Fetching configuration for session: {}", sessionId))
        .map(this::mapToSessionConfigResponse)
        .doOnNext(
            config ->
                log.atDebug().log(
                    "Retrieved configuration for session: {} with {} workflows",
                    sessionId,
                    config.workflows().size()))
        .doOnError(
            err ->
                log.atError()
                    .addArgument(sessionId)
                    .setCause(err)
                    .log("Failed to retrieve configuration for session: {}"));
  }

  /**
   * Map generic configuration map to SessionConfigResponse.
   *
   * @param config the configuration map from the store
   * @return SessionConfigResponse with all fields extracted
   */
  @SuppressWarnings("unchecked")
  private SessionConfigResponse mapToSessionConfigResponse(final Map<String, Object> config) {
    return new SessionConfigResponse(
        (String) config.get("sessionId"),
        (String) config.get("name"),
        (String) config.get("description"),
        (String) config.get("initiator"),
        (Map<String, String>) config.getOrDefault("tags", Map.of()),
        (String) config.get("projectPath"),
        (Map<String, WorkflowDefinition>) config.getOrDefault("workflows", Map.of()));
  }

  /**
   * Get all session configurations.
   *
   * <p>Retrieves full configuration for all sessions. Individual sessions that fail to load are
   * skipped with a warning; the flux completes with only successfully loaded sessions.
   *
   * @return Flux of all available session configurations
   */
  public Flux<SessionConfigResponse> getAllSessionConfigs() {
    return getSessionIds()
        .doOnSubscribe(_ -> log.atDebug().log("Fetching all session configurations"))
        .flatMap(
            sessionId ->
                getSessionConfig(sessionId)
                    .onErrorResume(
                        err -> {
                          log.atWarn()
                              .addKeyValue("sessionId", sessionId)
                              .setCause(err)
                              .log(
                                  "Skipping unreadable session config, continuing with other"
                                      + " sessions");
                          return Mono.empty();
                        }))
        .doOnComplete(() -> log.atDebug().log("All session configurations retrieved"));
  }

  /**
   * Get workflow for a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing the workflow definition
   */
  public Mono<WorkflowDefinition> getSessionWorkflow(
      @SessionId final String sessionId, @WorkflowId final String workflowId) {
    return workflowDefinitionStore
        .find(sessionId, workflowId)
        .doOnSubscribe(
            _ -> log.atDebug().log("Fetching workflow: {} for session: {}", workflowId, sessionId))
        .doOnSuccess(
            workflow ->
                log.atDebug().log(
                    "Retrieved workflow: {} with {} nodes and {} edges for session: {}",
                    workflowId,
                    workflow != null ? workflow.nodes().size() : 0,
                    workflow != null ? workflow.edges().size() : 0,
                    sessionId))
        .doOnError(
            err ->
                log.atError()
                    .addArgument(workflowId)
                    .addArgument(sessionId)
                    .setCause(err)
                    .log("Failed to retrieve workflow: {} for session: {}"));
  }

  /**
   * Get all workflow definitions for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of workflowId to WorkflowDefinition
   */
  public Mono<Map<String, WorkflowDefinition>> getSessionWorkflows(
      @SessionId final String sessionId) {
    return workflowDefinitionStore
        .findAll(sessionId)
        .doOnSubscribe(_ -> log.atDebug().log("Fetching all workflows for session: {}", sessionId))
        .doOnSuccess(
            workflows ->
                log.atDebug().log(
                    "Retrieved {} workflows for session: {}", workflows.size(), sessionId))
        .doOnError(
            err ->
                log.atError()
                    .addArgument(sessionId)
                    .setCause(err)
                    .log("Failed to retrieve workflows for session: {}"));
  }

  /**
   * Get the latest execution status per workflowId for a session.
   *
   * <p>Derives status from execution history, which is sorted most-recent-first. The first match
   * per workflowId is the latest. Workflows with no execution history are absent from the returned
   * map.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of workflowId to latest WorkflowExecutionSummary
   */
  public Mono<Map<String, WorkflowExecutionSummary>> getLatestExecutionStatusByWorkflow(
      @SessionId final String sessionId) {
    return controlBus
        .getHistory(sessionId)
        .map(
            history ->
                history.stream()
                    .collect(
                        Collectors.toMap(
                            WorkflowExecutionSummary::workflowId,
                            Function.identity(),
                            (first, second) -> first)))
        .doOnSubscribe(
            _ -> log.atDebug().log("Fetching execution history for session: {}", sessionId))
        .doOnError(
            err ->
                log.atError()
                    .addArgument(sessionId)
                    .setCause(err)
                    .log("Failed to retrieve execution history for session: {}"));
  }
}
