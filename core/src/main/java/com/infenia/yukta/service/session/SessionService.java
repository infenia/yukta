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
package com.infenia.yukta.service.session;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import jakarta.validation.Valid;
import java.util.Map;
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
   *
   * @param sessionId the session identifier
   * @return Mono containing map of configurations
   */
  public Mono<Map<String, Object>> getSessionConfig(@SessionId final String sessionId) {
    return configService
        .getAllConfigs(sessionId)
        .doOnSubscribe(_ -> log.atDebug().log("Fetching configuration for session: {}", sessionId))
        .doOnSuccess(
            config ->
                log.atDebug().log(
                    "Retrieved {} configuration entries for session: {}",
                    config != null ? config.size() : 0,
                    sessionId))
        .doOnError(
            err ->
                log.atError()
                    .addArgument(sessionId)
                    .setCause(err)
                    .log("Failed to retrieve configuration for session: {}"));
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
}
