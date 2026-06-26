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
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of {@link WorkflowDefinitionStore}.
 *
 * <p>Thread-safe via ConcurrentHashMap.
 */
@Slf4j
@ConditionalOnProperty(name = "yukta.session.store-type", havingValue = "in-memory")
@Component
@NoArgsConstructor
public class InMemoryWorkflowDefinitionStore implements WorkflowDefinitionStore {

  /** Map of session ID to workflow ID to workflow definition. */
  private final Map<String, Map<String, WorkflowDefinition>> store = new ConcurrentHashMap<>();

  @PostConstruct
  /* default */ void logInitialization() {
    log.info("Using WorkflowDefinitionStore with type: in-memory");
  }

  @Override
  public Mono<Void> save(final String sessionId, final WorkflowDefinition definition) {
    return Mono.fromRunnable(
        () -> {
          store
              .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
              .put(definition.workflowId(), definition);
          log.atDebug()
              .addKeyValue("sessionId", sessionId)
              .addKeyValue("workflowId", definition.workflowId())
              .log("Saved workflow definition");
        });
  }

  @Override
  public Mono<WorkflowDefinition> find(final String sessionId, final String workflowId) {
    return Mono.defer(
        () -> {
          final Map<String, WorkflowDefinition> session = store.get(sessionId);
          if (session == null) {
            return Mono.empty();
          }
          final WorkflowDefinition def = session.get(workflowId);
          return def != null ? Mono.just(def) : Mono.empty();
        });
  }

  @Override
  public Mono<Map<String, WorkflowDefinition>> findAll(final String sessionId) {
    return Mono.defer(
        () -> {
          final Map<String, WorkflowDefinition> session = store.get(sessionId);
          return Mono.just(session != null ? Map.copyOf(session) : Map.of());
        });
  }

  @Override
  public Mono<Void> remove(final String sessionId, final String workflowId) {
    return Mono.fromRunnable(
        () -> {
          final Map<String, WorkflowDefinition> session = store.get(sessionId);
          if (session != null) {
            session.remove(workflowId);
          }
        });
  }

  @Override
  public Mono<Void> removeAll(final String sessionId) {
    return Mono.fromRunnable(() -> store.remove(sessionId));
  }
}
