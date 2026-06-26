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

/**
 * In-memory implementation of SessionConfigStore. All configuration is session-based and updated
 * via API.
 */
@Slf4j
@ConditionalOnProperty(name = "yukta.session.store-type", havingValue = "in-memory")
@Component
@Validated
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.LinguisticNaming",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UseConcurrentHashMap"
})
public class InMemorySessionConfigStore implements SessionConfigStore {

  /** The session configuration properties. */
  private final SessionConfigProperties props;

  /** The workflow definition store for persisting workflow definitions. */
  private final WorkflowDefinitionStore workflowDefinitionStore;

  /** Map of session ID to session configuration. */
  private final Map<String, SessionConfig> sessions = new ConcurrentHashMap<>();

  @PostConstruct
  /* default */ void logInitialization() {
    log.info("Using SessionConfigStore with type: in-memory");
  }

  @Override
  public Mono<Void> applySessionConfig(@Valid final SessionConfigData data) {
    return Mono.fromRunnable(
            () -> {
              final String sessionId = data.sessionId();
              final SessionConfig config =
                  new SessionConfig(
                      data.projectPath(),
                      data.initiator(),
                      Instant.now().toString(),
                      data.tags(),
                      data.description());
              sessions.put(sessionId, config);
              log.atInfo()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("projectPath", config.projectPath())
                  .addKeyValue("initiator", config.initiator())
                  .addKeyValue("tagCount", config.tags().size())
                  .log("Applied session configuration");
            })
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
    final SessionConfig config = sessions.get(sessionId);
    return Mono.just(config != null ? config.projectPath() : "");
  }

  @Override
  public Mono<Void> setProjectPath(
      @SessionId final String sessionId, @ProjectPath final String path) {
    sessions.compute(
        sessionId,
        (_, config) -> {
          if (config == null) {
            return new SessionConfig(path, "", "", Map.of(), "");
          }
          return new SessionConfig(
              path,
              config.initiator(),
              config.initiatedTime(),
              config.tags(),
              config.description());
        });
    log.atDebug()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue("projectPath", path)
        .log("Set project path for session");
    return Mono.empty();
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
    final SessionConfig config = sessions.get(sessionId);
    return Mono.just(config != null ? config.description() : "");
  }

  @Override
  public Mono<Void> setDescription(@SessionId final String sessionId, final String description) {
    if (description != null) {
      final boolean[] isNew = {false};
      sessions.compute(
          sessionId,
          (_, config) -> {
            if (config == null) {
              isNew[0] = true;
              return new SessionConfig("", "", "", Map.of(), description);
            } else if (config.description().isEmpty()) {
              isNew[0] = true;
              return new SessionConfig(
                  config.projectPath(),
                  config.initiator(),
                  config.initiatedTime(),
                  config.tags(),
                  description);
            }
            return config;
          });
      log.atDebug()
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("isNew", isNew[0])
          .log("Set description for session");
    }
    return Mono.empty();
  }

  @Override
  public Mono<String> getInitiator(@SessionId final String sessionId) {
    final SessionConfig config = sessions.get(sessionId);
    return Mono.just(config != null ? config.initiator() : "");
  }

  @Override
  public Mono<Void> setInitiator(@SessionId final String sessionId, final String initiator) {
    if (initiator != null) {
      final boolean[] isNew = {false};
      sessions.compute(
          sessionId,
          (_, config) -> {
            if (config == null) {
              isNew[0] = true;
              return new SessionConfig("", initiator, "", Map.of(), "");
            } else if (config.initiator().isEmpty()) {
              isNew[0] = true;
              return new SessionConfig(
                  config.projectPath(),
                  initiator,
                  config.initiatedTime(),
                  config.tags(),
                  config.description());
            }
            return config;
          });
      log.atDebug()
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("initiator", initiator)
          .addKeyValue("isNew", isNew[0])
          .log("Set initiator for session");
    }
    return Mono.empty();
  }

  @Override
  public Mono<String> getInitiatedTime(@SessionId final String sessionId) {
    final SessionConfig config = sessions.get(sessionId);
    return Mono.just(config != null ? config.initiatedTime() : "");
  }

  @Override
  public Mono<Void> setInitiatedTime(
      @SessionId final String sessionId, final String initiatedTime) {
    if (initiatedTime != null) {
      final boolean[] isNew = {false};
      sessions.compute(
          sessionId,
          (_, config) -> {
            if (config == null) {
              isNew[0] = true;
              return new SessionConfig("", "", initiatedTime, Map.of(), "");
            } else if (config.initiatedTime().isEmpty()) {
              isNew[0] = true;
              return new SessionConfig(
                  config.projectPath(),
                  config.initiator(),
                  initiatedTime,
                  config.tags(),
                  config.description());
            }
            return config;
          });
      log.atDebug()
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("isNew", isNew[0])
          .log("Set initiated time for session");
    }
    return Mono.empty();
  }

  @Override
  public Mono<Map<String, String>> getTags(@SessionId final String sessionId) {
    final SessionConfig config = sessions.get(sessionId);
    return Mono.just(config != null ? config.tags() : Map.of());
  }

  @Override
  public Mono<Void> setTags(@SessionId final String sessionId, final Map<String, String> tags) {
    if (tags != null) {
      final boolean[] isNew = {false};
      sessions.compute(
          sessionId,
          (_, config) -> {
            if (config == null) {
              isNew[0] = true;
              return new SessionConfig("", "", "", tags, "");
            } else if (config.tags().isEmpty()) {
              isNew[0] = true;
              return new SessionConfig(
                  config.projectPath(),
                  config.initiator(),
                  config.initiatedTime(),
                  tags,
                  config.description());
            }
            return config;
          });
      log.atDebug()
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("tagCount", tags.size())
          .addKeyValue("isNew", isNew[0])
          .log("Set tags for session");
    }
    return Mono.empty();
  }

  @Override
  public Mono<Map<String, Object>> getAllConfigs(@SessionId final String sessionId) {
    return sessionExists(sessionId)
        ? Mono.zip(
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
              log.atDebug()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("configCount", configs.size())
                  .log("Retrieved all configurations for session");
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
            workflowDefinitionStore.findAll(sessionId))
        : Mono.empty();
  }

  private boolean sessionExists(final String sessionId) {
    return sessions.containsKey(sessionId);
  }

  @Override
  public Flux<String> getSessionIds() {
    log.atDebug()
        .addKeyValue("sessionCount", sessions.size())
        .log("Retrieved all active session IDs");
    return Flux.fromIterable(sessions.keySet());
  }
}
