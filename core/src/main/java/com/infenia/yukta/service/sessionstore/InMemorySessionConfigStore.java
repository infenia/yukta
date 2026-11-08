// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.sessionstore;

import com.infenia.yukta.model.session.SessionConfig;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionSnapshot;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
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
@SuppressWarnings({"PMD.TooManyMethods", "PMD.LinguisticNaming", "PMD.AvoidDuplicateLiterals"})
public class InMemorySessionConfigStore implements SessionConfigStore {

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
                      data.description(),
                      data.name());
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
  public Mono<String> getDescription(@SessionId final String sessionId) {
    final SessionConfig config = sessions.get(sessionId);
    return Mono.just(config != null ? config.description() : "");
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
              return new SessionConfig("", initiator, "", Map.of(), "", "");
            } else if (config.initiator().isEmpty()) {
              isNew[0] = true;
              return new SessionConfig(
                  config.projectPath(),
                  initiator,
                  config.initiatedTime(),
                  config.tags(),
                  config.description(),
                  config.name());
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
  public Mono<Map<String, String>> getTags(@SessionId final String sessionId) {
    final SessionConfig config = sessions.get(sessionId);
    return Mono.just(config != null ? config.tags() : Map.of());
  }

  @Override
  public Mono<SessionSnapshot> getAllConfigs(@SessionId final String sessionId) {
    final var config = sessions.get(sessionId);
    return config != null
        ? Mono.zip(
            arr -> {
              final SessionSnapshot configs =
                  SessionSnapshot.fromValues(sessionId, config.name(), arr);
              log.atDebug()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("workflows", configs.workflows().size())
                  .log("Retrieved all configurations for session");
              return configs;
            },
            getProjectPath(sessionId),
            getInitiator(sessionId),
            getInitiatedTime(sessionId),
            getTags(sessionId),
            getDescription(sessionId),
            workflowDefinitionStore.findAll(sessionId))
        : Mono.empty();
  }

  @Override
  public Mono<Void> removeSession(@SessionId final String sessionId) {
    return Mono.fromRunnable(() -> sessions.remove(sessionId));
  }

  @Override
  public Flux<String> getSessionIds() {
    log.atDebug()
        .addKeyValue("sessionCount", sessions.size())
        .log("Retrieved all active session IDs");
    return Flux.fromIterable(sessions.keySet());
  }
}
