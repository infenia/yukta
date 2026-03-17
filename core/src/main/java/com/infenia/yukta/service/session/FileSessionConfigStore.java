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

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.validation.ProjectPath;
import com.infenia.yukta.validation.SessionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * File-based implementation of SessionConfigStore that persists session configuration to disk. Each
 * session's configuration is stored as JSON in a separate file for durability and easy inspection.
 *
 * <p>This implementation is created by the SessionConfigStoreFactory and is not a Spring component
 * itself.
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.DataClass",
  "PMD.VariableCanBeInlined",
  "PMD.UseConcurrentHashMap",
  "PMD.UseExplicitTypes"
})
public class FileSessionConfigStore implements SessionConfigStore {

  private final SessionConfigProperties props;
  private final ObjectMapper objectMapper;

  // In-memory cache for faster access
  private final Map<String, SessionConfig> sessionCache = new ConcurrentHashMap<>();

  /** Internal data class for serializing session configuration to file. */
  public static class SessionConfig {
    public String sessionId;
    public String projectPath;
    public Map<String, WorkflowDefinition> workflows;
    public String description;
    public String initiator;
    public String initiatedTime;
    public Map<String, String> tags;

    /**
     * Full constructor.
     *
     * @param sessionId the session ID
     * @param projectPath the project path
     * @param workflows the workflows map
     * @param description the description
     * @param initiator the initiator
     * @param initiatedTime the initiated time
     * @param tags the tags
     */
    public SessionConfig(
        final String sessionId,
        final String projectPath,
        final Map<String, WorkflowDefinition> workflows,
        final String description,
        final String initiator,
        final String initiatedTime,
        final Map<String, String> tags) {
      this.sessionId = sessionId;
      this.projectPath = projectPath;
      this.workflows = workflows;
      this.description = description;
      this.initiator = initiator;
      this.initiatedTime = initiatedTime;
      this.tags = tags;
    }
  }

  @Override
  public Mono<Void> applySessionConfig(@Valid final SessionConfigData data) {
    return Mono.fromCallable(
            () -> {
              final SessionConfig config =
                  new SessionConfig(
                      data.sessionId(),
                      data.projectPath(),
                      data.workflows(),
                      data.description(),
                      data.initiator(),
                      Instant.now().toString(),
                      data.tags());
              return config;
            })
        .flatMap(config -> saveSessionConfig(data.sessionId(), config))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<String> getProjectPath(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.projectPath != null ? config.projectPath : "")
        .defaultIfEmpty("");
  }

  @Override
  public Mono<Void> setProjectPath(
      @SessionId final String sessionId, @ProjectPath final String path) {
    return loadSessionConfig(sessionId)
        .defaultIfEmpty(new SessionConfig(sessionId, "", Map.of(), "", "", "", Map.of()))
        .flatMap(
            config -> {
              config.projectPath = path;
              return saveSessionConfig(sessionId, config);
            });
  }

  @Override
  public Mono<Map<String, WorkflowDefinition>> getWorkflows(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(
            config ->
                config.workflows != null ? config.workflows : Map.<String, WorkflowDefinition>of())
        .defaultIfEmpty(Map.of());
  }

  @Override
  public Mono<WorkflowDefinition> getWorkflow(
      @SessionId final String sessionId, final String workflowId) {
    return getWorkflows(sessionId)
        .flatMap(
            workflows -> {
              final WorkflowDefinition result = workflows.get(workflowId);
              return result != null ? Mono.just(result) : Mono.empty();
            });
  }

  @Override
  public Mono<Void> setWorkflows(
      @SessionId final String sessionId,
      @NotEmpty(message = "Workflows cannot be empty")
          final Map<String, WorkflowDefinition> workflows) {
    return loadSessionConfig(sessionId)
        .defaultIfEmpty(new SessionConfig(sessionId, "", Map.of(), "", "", "", Map.of()))
        .flatMap(
            config -> {
              config.workflows = workflows;
              return saveSessionConfig(sessionId, config);
            });
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
        .map(config -> config.description != null ? config.description : "")
        .defaultIfEmpty("");
  }

  @Override
  public Mono<Void> setDescription(@SessionId final String sessionId, final String description) {
    if (description == null) {
      return Mono.empty();
    }
    return Mono.fromCallable(
            () -> {
              final Path configPath = getSessionConfigPath(sessionId);
              if (Files.exists(configPath)) {
                final String json = Files.readString(configPath, StandardCharsets.UTF_8);
                final SessionConfig existing = objectMapper.readValue(json, SessionConfig.class);
                if (existing.description != null && !existing.description.isEmpty()) {
                  return existing;
                }
                existing.description = description;
                return existing;
              }
              return new SessionConfig(sessionId, "", Map.of(), description, "", "", Map.of());
            })
        .flatMap(config -> saveSessionConfig(sessionId, config))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<String> getInitiator(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.initiator != null ? config.initiator : "")
        .defaultIfEmpty("");
  }

  @Override
  public Mono<Void> setInitiator(@SessionId final String sessionId, final String initiator) {
    if (initiator == null) {
      return Mono.empty();
    }
    return Mono.fromCallable(
            () -> {
              // Check if initiator already set
              final Path configPath = getSessionConfigPath(sessionId);
              if (Files.exists(configPath)) {
                final String json = Files.readString(configPath, StandardCharsets.UTF_8);
                final SessionConfig existing = objectMapper.readValue(json, SessionConfig.class);
                if (existing.initiator != null && !existing.initiator.isEmpty()) {
                  return existing; // Already set, don't update
                }
                existing.initiator = initiator;
                return existing;
              }
              // Create new
              return new SessionConfig(sessionId, "", Map.of(), "", initiator, "", Map.of());
            })
        .flatMap(config -> saveSessionConfig(sessionId, config))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<String> getInitiatedTime(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.initiatedTime != null ? config.initiatedTime : "")
        .defaultIfEmpty("");
  }

  @Override
  public Mono<Void> setInitiatedTime(
      @SessionId final String sessionId, final String initiatedTime) {
    if (initiatedTime == null) {
      return Mono.empty();
    }
    return Mono.fromCallable(
            () -> {
              final Path configPath = getSessionConfigPath(sessionId);
              if (Files.exists(configPath)) {
                final String json = Files.readString(configPath, StandardCharsets.UTF_8);
                final SessionConfig existing = objectMapper.readValue(json, SessionConfig.class);
                if (existing.initiatedTime != null && !existing.initiatedTime.isEmpty()) {
                  return existing;
                }
                existing.initiatedTime = initiatedTime;
                return existing;
              }
              return new SessionConfig(sessionId, "", Map.of(), "", "", initiatedTime, Map.of());
            })
        .flatMap(config -> saveSessionConfig(sessionId, config))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<Map<String, String>> getTags(@SessionId final String sessionId) {
    return loadSessionConfig(sessionId)
        .map(config -> config.tags != null ? config.tags : Map.<String, String>of())
        .defaultIfEmpty(Map.of());
  }

  @Override
  public Mono<Void> setTags(@SessionId final String sessionId, final Map<String, String> tags) {
    if (tags == null) {
      return Mono.empty();
    }
    return Mono.fromCallable(
            () -> {
              final Path configPath = getSessionConfigPath(sessionId);
              if (Files.exists(configPath)) {
                final String json = Files.readString(configPath, StandardCharsets.UTF_8);
                final SessionConfig existing = objectMapper.readValue(json, SessionConfig.class);
                if (existing.tags != null && !existing.tags.isEmpty()) {
                  return existing;
                }
                existing.tags = Map.copyOf(tags);
                return existing;
              }
              return new SessionConfig(sessionId, "", Map.of(), "", "", "", Map.copyOf(tags));
            })
        .flatMap(config -> saveSessionConfig(sessionId, config))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
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
              return objectMapper.readValue(json, SessionConfig.class);
            })
        .doOnNext(
            config -> {
              sessionCache.put(sessionId, config);
            })
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
              final String json = objectMapper.writeValueAsString(config);
              Files.writeString(
                  configPath,
                  json,
                  StandardCharsets.UTF_8,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.TRUNCATE_EXISTING);
              return true;
            })
        .doOnNext(
            unused -> {
              sessionCache.put(sessionId, config);
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
    try (var stream = Files.list(sessionDir)) {
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
        .flatMapMany(Flux::fromIterable)
        .map(this::extractSessionIdFromFile)
        .subscribeOn(Schedulers.boundedElastic());
  }
}
