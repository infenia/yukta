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
package com.infenia.jagratha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.validation.SessionId;
import com.infenia.jagratha.validation.WorkflowId;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service for managing session lifecycle and configuration. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class SessionService {

  private final AppConfigService configService;
  private final ObjectMapper objectMapper;
  private final WorkflowOrchestrator orchestrator;

  private static final String SESS_ID_PATTERN = "^(?!.*\\.\\.)[^/\\\\]*$";

  /**
   * Apply configuration overrides for a session.
   *
   * @param data the configuration data
   * @return Mono that completes when config is applied
   */
  public Mono<Void> applyConfigOverrides(@Valid final AppConfigData data) {
    return Mono.defer(
            () -> {
              final Mono<Void> projectPathMono =
                  configService.setProjectPath(data.sessionId(), data.projectPath());
              final Mono<Void> workflowMono =
                  configService.setWorkflows(data.sessionId(), data.workflows());
              final Mono<Void> descriptionMono =
                  configService.setDescription(data.sessionId(), data.description());
              final Mono<Void> initiatorMono =
                  configService.setInitiator(data.sessionId(), data.initiator());
              final Mono<Void> tagsMono = configService.setTags(data.sessionId(), data.tags());
              final Mono<Void> initiatedTimeMono =
                  configService.setInitiatedTime(data.sessionId(), Instant.now().toString());

              return Mono.when(
                      projectPathMono,
                      workflowMono,
                      descriptionMono,
                      initiatorMono,
                      tagsMono,
                      initiatedTimeMono)
                  .then(
                      Flux.fromIterable(data.workflows().values())
                          .flatMap(orchestrator::prepareWorkflow)
                          .then());
            })
        .then(saveConfigToDisk(data.sessionId()));
  }

  /**
   * Save configuration to disk.
   *
   * @param sessionId the session identifier
   * @return Mono that completes when config is saved
   */
  public Mono<Void> saveConfigToDisk(@SessionId final String sessionId) {
    return configService
        .getResultLogDir(sessionId)
        .filter(resultsDir -> !resultsDir.isEmpty())
        .flatMap(
            resultsDir -> {
              final Path dirPath = Path.of(resultsDir).resolve(sessionId);
              final Path configFile = dirPath.resolve("config.json");

              return Mono.fromCallable(
                      () -> {
                        Files.createDirectories(dirPath);
                        return dirPath;
                      })
                  .then(configService.getAllConfigs(sessionId))
                  .flatMap(
                      configs ->
                          Mono.fromCallable(
                              () -> {
                                Files.writeString(
                                    configFile, objectMapper.writeValueAsString(configs));
                                return null;
                              }));
            })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(
            e -> {
              log.error("Failed to save config for session {}", sessionId, e);
              return Mono.empty();
            })
        .then();
  }

  /**
   * Get all session IDs that have logs or configurations on disk.
   *
   * @return Flux of session IDs
   */
  public Flux<String> getAllSessionsOnDisk() {
    return Mono.zip(configService.getResultLogDir(null), configService.getFileLogDir(null))
        .flatMapMany(
            tuple -> {
              final Set<String> sessions = new TreeSet<>();
              final String resultsDir = tuple.getT1();
              final String fileLogDir = tuple.getT2();

              addSessionIds(sessions, resultsDir);
              addSessionIds(sessions, fileLogDir);

              return Flux.fromIterable(sessions);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void addSessionIds(final Set<String> sessions, final String baseDir) {
    if (baseDir == null || baseDir.isEmpty()) {
      return;
    }
    try {
      final Path path = Path.of(baseDir);
      if (Files.exists(path) && Files.isDirectory(path)) {
        try (Stream<Path> dirs = Files.list(path)) {
          dirs.filter(Files::isDirectory)
              .map(p -> p.getFileName().toString())
              .filter(name -> name.matches(SESS_ID_PATTERN))
              .forEach(sessions::add);
        }
      }
    } catch (IOException e) {
      if (log.isWarnEnabled()) {
        log.warn("Failed to list sessions from directory: {}", baseDir, e);
      }
    }
  }

  /**
   * Get all active session IDs.
   *
   * @return Flux of active session IDs
   */
  public Flux<String> getActiveSessions() {
    return configService.getActiveSessionIds();
  }

  /**
   * Get all history session IDs (on disk but not active).
   *
   * @return Flux of history session IDs
   */
  public Flux<String> getHistorySessions() {
    return getAllSessionsOnDisk()
        .collectList()
        .flatMapMany(
            allOnDisk ->
                configService
                    .getActiveSessionIds()
                    .collectList()
                    .flatMapMany(
                        active ->
                            Flux.fromIterable(
                                allOnDisk.stream().filter(s -> !active.contains(s)).toList())));
  }

  /**
   * Get configuration for a session, from memory or disk.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of configurations
   */
  public Mono<Map<String, Object>> getSessionConfig(@SessionId final String sessionId) {
    return configService
        .isActive(sessionId)
        .flatMap(
            active -> {
              if (active) {
                return configService.getAllConfigs(sessionId);
              }
              return readConfigFromDisk(sessionId)
                  .switchIfEmpty(configService.getAllConfigs(sessionId));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @SuppressWarnings("unchecked")
  private Mono<Map<String, Object>> readConfigFromDisk(final String sessionId) {
    return configService
        .getResultLogDir(sessionId)
        .flatMap(
            resultsDir ->
                Mono.fromCallable(
                    () -> {
                      final Path configFile =
                          Path.of(resultsDir).resolve(sessionId).resolve("config.json");
                      if (Files.exists(configFile)) {
                        return (Map<String, Object>)
                            objectMapper.readValue(
                                Files.readString(configFile, StandardCharsets.UTF_8), Map.class);
                      }
                      return null;
                    }))
        .onErrorResume(
            e -> {
              if (log.isWarnEnabled()) {
                log.warn(
                    "Failed to read config from disk for session {}: {}",
                    sessionId,
                    e.getMessage());
              }
              return Mono.empty();
            });
  }

  /**
   * Get workflow for a session, from memory or disk.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing the workflow definition
   */
  public Mono<WorkflowDefinition> getSessionWorkflow(
      @SessionId final String sessionId, @WorkflowId final String workflowId) {
    return configService
        .isActive(sessionId)
        .flatMap(
            active -> {
              if (active) {
                return configService.getWorkflow(sessionId, workflowId);
              }
              return getWorkflowFromDisk(sessionId, workflowId);
            });
  }

  private Mono<WorkflowDefinition> getWorkflowFromDisk(
      final String sessionId, final String workflowId) {
    return getSessionConfig(sessionId)
        .flatMap(
            config -> {
              final Object workflowsObj = config.get("workflows");
              if (workflowsObj instanceof Map workflows) {
                final Object workflow = workflows.get(workflowId);
                if (workflow != null) {
                  return parseWorkflow(workflow, sessionId, workflowId);
                }
              }
              return configService.getWorkflow(sessionId, workflowId);
            });
  }

  private Mono<WorkflowDefinition> parseWorkflow(
      final Object workflow, final String sessionId, final String workflowId) {
    return Mono.fromCallable(
            () -> {
              final String json = objectMapper.writeValueAsString(workflow);
              return objectMapper.readValue(json, WorkflowDefinition.class);
            })
        .onErrorResume(
            e -> {
              if (log.isWarnEnabled()) {
                log.warn(
                    "Failed to parse workflow {} from config for session {}: {}",
                    workflowId,
                    sessionId,
                    e.getMessage());
              }
              return Mono.empty();
            });
  }
}
