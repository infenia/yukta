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
package com.infenia.yukta.service.session.store;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.session.SessionConfig;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.validation.ProjectPath;
import com.infenia.yukta.validation.SessionId;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * File-based implementation of SessionConfigStore that persists session configuration to disk. Each
 * session's configuration is stored as JSON in a separate file for durability and easy inspection.
 *
 * <p>This component is conditionally created as a Spring bean when {@code yukta.session.store-type}
 * is set to {@code file}.
 */
@Component
@ConditionalOnProperty(name = "yukta.session.store-type", havingValue = "file")
@Slf4j
@Validated
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UseConcurrentHashMap"
})
public class FileSessionConfigStore implements SessionConfigStore {

  /** The session configuration properties. */
  private final SessionConfigProperties props;

  /** The ObjectMapper for JSON serialization/deserialization. */
  private final ObjectMapper objectMapper;

  /** The workflow definition store for persisting workflow definitions. */
  private final WorkflowDefinitionStore workflowDefinitionStore;

  /** Cache of session configurations by session ID. */
  private final Map<String, SessionConfig> sessionCache = new ConcurrentHashMap<>();

  @PostConstruct
  /* default */ void logInitialization() {
    log.info("Using SessionConfigStore with type: file");
  }

  @Override
  public Mono<Void> applySessionConfig(@Valid final SessionConfigData data) {
    return Mono.fromCallable(
            () ->
                new SessionConfig(
                    data.projectPath(),
                    data.initiator(),
                    Instant.now().toString(),
                    data.tags(),
                    data.description()))
        .flatMap(
            config ->
                saveSessionConfig(data.sessionId(), config)
                    .doOnSuccess(
                        _ ->
                            log.atInfo()
                                .addKeyValue("sessionId", data.sessionId())
                                .addKeyValue("projectPath", config.projectPath())
                                .addKeyValue("initiator", config.initiator())
                                .addKeyValue("tagCount", config.tags().size())
                                .log("Applied session configuration")))
        .then(
            data.workflows().isEmpty()
                ? Mono.empty()
                : Flux.fromIterable(data.workflows().values())
                    .flatMap(def -> workflowDefinitionStore.save(data.sessionId(), def))
                    .then(
                        Mono.fromRunnable(
                            () ->
                                log.atDebug()
                                    .addKeyValue("sessionId", data.sessionId())
                                    .addKeyValue("workflowCount", data.workflows().size())
                                    .log("Saved workflows for session"))));
  }

  @Override
  public Mono<String> getProjectPath(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.projectPath() != null ? config.projectPath() : "")
        .defaultIfEmpty("");
  }

  @Override
  public Mono<Void> setProjectPath(
      @SessionId final String sessionId, @ProjectPath final String path) {
    return loadSessionConfig(sessionId)
        .defaultIfEmpty(new SessionConfig("", "", "", Map.of(), ""))
        .flatMap(
            config ->
                saveSessionConfig(
                    sessionId,
                    new SessionConfig(
                        path,
                        config.initiator(),
                        config.initiatedTime(),
                        config.tags(),
                        config.description())))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("projectPath", path)
                    .log("Set project path for session"));
  }

  @Override
  public Mono<Long> getExecutionTimeout(@SessionId final String sessionId) {
    return Mono.just(props.getExecutionTimeoutSeconds());
  }

  @Override
  public Mono<String> getFileLogDir(final String sessionId) {
    return Mono.just(props.getBaseDir() + "/" + props.getFileLogSubDir());
  }

  @Override
  public Mono<String> getResultLogDir(final String sessionId) {
    return Mono.just(props.getBaseDir() + "/" + props.getResultLogSubDir());
  }

  @Override
  public Mono<String> getDescription(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.description() != null ? config.description() : "")
        .defaultIfEmpty("");
  }

  @Override
  public Mono<Void> setDescription(@SessionId final String sessionId, final String description) {
    if (description == null) {
      return Mono.empty();
    }
    return loadSessionConfig(sessionId)
        .switchIfEmpty(Mono.just(new SessionConfig("", "", "", Map.of(), "")))
        .filter(config -> config.description() == null || config.description().isEmpty())
        .flatMap(
            config ->
                saveSessionConfig(
                    sessionId,
                    new SessionConfig(
                        config.projectPath(),
                        config.initiator(),
                        config.initiatedTime(),
                        config.tags(),
                        description)))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("description", description)
                    .log("Set description for session"))
        .then();
  }

  @Override
  public Mono<String> getInitiator(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.initiator() != null ? config.initiator() : "")
        .defaultIfEmpty("");
  }

  @Override
  public Mono<Void> setInitiator(@SessionId final String sessionId, final String initiator) {
    if (initiator == null) {
      return Mono.empty();
    }
    return loadSessionConfig(sessionId)
        .switchIfEmpty(Mono.just(new SessionConfig("", "", "", Map.of(), "")))
        .filter(config -> config.initiator() == null || config.initiator().isEmpty())
        .flatMap(
            config ->
                saveSessionConfig(
                    sessionId,
                    new SessionConfig(
                        config.projectPath(),
                        initiator,
                        config.initiatedTime(),
                        config.tags(),
                        config.description())))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("initiator", initiator)
                    .log("Set initiator for session"))
        .then();
  }

  @Override
  public Mono<String> getInitiatedTime(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.initiatedTime() != null ? config.initiatedTime() : "")
        .defaultIfEmpty("");
  }

  @Override
  public Mono<Void> setInitiatedTime(
      @SessionId final String sessionId, final String initiatedTime) {
    if (initiatedTime == null) {
      return Mono.empty();
    }
    return loadSessionConfig(sessionId)
        .switchIfEmpty(Mono.just(new SessionConfig("", "", "", Map.of(), "")))
        .filter(config -> config.initiatedTime() == null || config.initiatedTime().isEmpty())
        .flatMap(
            config ->
                saveSessionConfig(
                    sessionId,
                    new SessionConfig(
                        config.projectPath(),
                        config.initiator(),
                        initiatedTime,
                        config.tags(),
                        config.description())))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .log("Set initiated time for session"))
        .then();
  }

  @Override
  public Mono<Map<String, String>> getTags(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.tags() != null ? config.tags() : Map.<String, String>of())
        .defaultIfEmpty(Map.of());
  }

  @Override
  public Mono<Void> setTags(@SessionId final String sessionId, final Map<String, String> tags) {
    if (tags == null) {
      return Mono.empty();
    }
    return loadSessionConfig(sessionId)
        .switchIfEmpty(Mono.just(new SessionConfig("", "", "", Map.of(), "")))
        .filter(config -> config.tags() == null || config.tags().isEmpty())
        .flatMap(
            config ->
                saveSessionConfig(
                    sessionId,
                    new SessionConfig(
                        config.projectPath(),
                        config.initiator(),
                        config.initiatedTime(),
                        tags,
                        config.description())))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("tagCount", tags.size())
                    .log("Set tags for session"))
        .then();
  }

  @Override
  public Mono<Map<String, Object>> getAllConfigs(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .flatMap(
            ignored ->
                Mono.zip(
                    arr -> {
                      final Map<String, Object> configs = new java.util.LinkedHashMap<>();
                      configs.put("projectPath", arr[0]);
                      configs.put("executionTimeout", arr[1]);
                      configs.put("fileLogDir", arr[2]);
                      configs.put("resultLogDir", arr[3]);
                      configs.put("initiator", arr[4]);
                      configs.put("initiatedTime", arr[5]);
                      configs.put("tags", arr[6]);
                      configs.put("description", arr[7]);
                      configs.put("workflows", arr[8]);
                      return configs;
                    },
                    getProjectPath(sessionId),
                    getExecutionTimeout(sessionId),
                    getFileLogDir(sessionId),
                    getResultLogDir(sessionId),
                    getInitiator(sessionId),
                    getInitiatedTime(sessionId),
                    getTags(sessionId),
                    getDescription(sessionId),
                    workflowDefinitionStore.findAll(sessionId)));
  }

  /* default */ record SessionConfigFile(
      String projectPath,
      String initiator,
      String initiatedTime,
      Map<String, String> tags,
      String description) {}

  /**
   * Load session configuration from file (with caching).
   *
   * @param sessionId the session ID
   * @return Mono containing the session config (empty if not found)
   */
  private Mono<SessionConfig> loadSessionConfig(final String sessionId) {
    if (sessionCache.containsKey(sessionId)) {
      return Mono.just(sessionCache.get(sessionId));
    }

    return Mono.fromCallable(
            () -> {
              final Path configPath = getSessionConfigPath(sessionId);
              if (!Files.exists(configPath)) {
                return null;
              }
              final String json = Files.readString(configPath, StandardCharsets.UTF_8);
              final SessionConfigFile fileConfig =
                  objectMapper.readValue(json, SessionConfigFile.class);
              return new SessionConfig(
                  fileConfig.projectPath(),
                  fileConfig.initiator(),
                  fileConfig.initiatedTime(),
                  fileConfig.tags(),
                  fileConfig.description());
            })
        .doOnNext(config -> sessionCache.put(sessionId, config))
        .flatMap(Mono::just)
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Save session configuration to file (with cache update).
   *
   * @param sessionId the session ID
   * @param config the session configuration
   * @return Mono that completes when the write is done
   */
  private Mono<Void> saveSessionConfig(final String sessionId, final SessionConfig config) {
    return Mono.fromCallable(
            () -> {
              ensureSessionDir();
              final Path configPath = getSessionConfigPath(sessionId);
              final SessionConfigFile fileConfig =
                  new SessionConfigFile(
                      config.projectPath(),
                      config.initiator(),
                      config.initiatedTime(),
                      config.tags(),
                      config.description());
              final String json = objectMapper.writeValueAsString(fileConfig);
              Files.writeString(
                  configPath,
                  json,
                  StandardCharsets.UTF_8,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.TRUNCATE_EXISTING);
              return config;
            })
        .doOnNext(
            savedConfig -> {
              sessionCache.put(sessionId, savedConfig);
              log.debug("Session config saved for sessionId: {}", sessionId);
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  /**
   * Get the file path for a session's configuration.
   *
   * @param sessionId the session ID
   * @return the path to the session config file
   */
  private Path getSessionConfigPath(final String sessionId) {
    return Path.of(props.getBaseDir()).resolve("sessions").resolve(sessionId + ".json");
  }

  /**
   * Ensure the session storage directory exists.
   *
   * @throws IOException if directory creation fails
   */
  private void ensureSessionDir() throws IOException {
    final Path sessionDir = Path.of(props.getBaseDir()).resolve("sessions");
    Files.createDirectories(sessionDir);
  }

  /**
   * Get all session configuration files.
   *
   * @return list of session file paths
   * @throws IOException if directory reading fails
   */
  private java.util.List<Path> getSessionFiles() throws IOException {
    final Path sessionDir = Path.of(props.getBaseDir()).resolve("sessions");
    if (!Files.exists(sessionDir)) {
      return java.util.List.of();
    }
    try (final var stream = Files.list(sessionDir)) {
      return stream.filter(path -> path.toString().endsWith(".json")).toList();
    }
  }

  /**
   * Extract session ID from a file path.
   *
   * @param path the file path
   * @return the session ID
   */
  private String extractSessionIdFromFile(final Path path) {
    final String filename = path.getFileName().toString();
    return filename.substring(0, filename.length() - 5); // Remove .json extension
  }

  @Override
  public Flux<String> getSessionIds() {
    return Mono.fromCallable(this::getSessionFiles)
        .doOnNext(
            files ->
                log.atDebug()
                    .addKeyValue("sessionCount", files.size())
                    .log("Retrieved all active session IDs"))
        .flatMapMany(Flux::fromIterable)
        .map(this::extractSessionIdFromFile)
        .subscribeOn(Schedulers.boundedElastic());
  }
}
