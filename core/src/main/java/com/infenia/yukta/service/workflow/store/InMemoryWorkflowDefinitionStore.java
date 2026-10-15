// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
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

  /** Saved versions by session and workflow, oldest first; the newest {@value #MAX_REVISIONS}. */
  private final Map<String, Deque<Revision>> history = new ConcurrentHashMap<>();

  /** Most versions kept per workflow. */
  public static final int MAX_REVISIONS = 20;

  /** Notes by session, workflow and version. */
  private final Map<String, String> notes = new ConcurrentHashMap<>();

  /** A saved version. */
  private record Revision(DefinitionRevision info, WorkflowDefinition definition) {}

  @PostConstruct
  /* default */ void logInitialization() {
    log.info("Using WorkflowDefinitionStore with type: in-memory");
  }

  @Override
  public Mono<Void> save(final String sessionId, final WorkflowDefinition definition) {
    return Mono.fromRunnable(
        () -> {
          final WorkflowDefinition previous =
              store
                  .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                  .put(definition.workflowId(), definition);
          if (!definition.equals(previous)) {
            remember(sessionId, definition);
          }
          log.atDebug()
              .addKeyValue("sessionId", sessionId)
              .addKeyValue("workflowId", definition.workflowId())
              .log("Saved workflow definition");
        });
  }

  private void remember(final String sessionId, final WorkflowDefinition definition) {
    final Deque<Revision> revisions =
        history.computeIfAbsent(
            sessionId + "\0" + definition.workflowId(), _ -> new ConcurrentLinkedDeque<>());
    final Instant now = Instant.now();
    revisions.addLast(
        new Revision(
            new DefinitionRevision(DefinitionRevisions.idOf(now, definition), now), definition));
    while (revisions.size() > MAX_REVISIONS) {
      final Revision dropped = revisions.pollFirst();
      if (dropped != null) {
        notes.remove(noteKey(sessionId, definition.workflowId(), dropped.info().id()));
      }
    }
  }

  @Override
  public Flux<DefinitionRevision> revisions(final String sessionId, final String workflowId) {
    return Flux.defer(
        () -> {
          final List<DefinitionRevision> newestFirst =
              history
                  .getOrDefault(sessionId + "\0" + workflowId, new ConcurrentLinkedDeque<>())
                  .stream()
                  .map(Revision::info)
                  .map(info -> info.withNote(notes.get(noteKey(sessionId, workflowId, info.id()))))
                  .sorted(Comparator.comparing(DefinitionRevision::id).reversed())
                  .toList();
          return Flux.fromIterable(newestFirst);
        });
  }

  private static String noteKey(
      final String sessionId, final String workflowId, final String revisionId) {
    return sessionId + "\0" + workflowId + "\0" + revisionId;
  }

  @Override
  public Mono<Void> setNote(
      final String sessionId, final String workflowId, final String revisionId, final String note) {
    return findRevision(sessionId, workflowId, revisionId)
        .switchIfEmpty(Mono.error(() -> new NotFoundException("Version not found: " + revisionId)))
        .doOnNext(
            _ -> {
              final String key = noteKey(sessionId, workflowId, revisionId);
              if (note == null || note.isBlank()) {
                notes.remove(key);
              } else {
                notes.put(key, note);
              }
            })
        .then();
  }

  @Override
  public Mono<WorkflowDefinition> findRevision(
      final String sessionId, final String workflowId, final String revisionId) {
    return Mono.defer(
        () ->
            Mono.justOrEmpty(
                history
                    .getOrDefault(sessionId + "\0" + workflowId, new ConcurrentLinkedDeque<>())
                    .stream()
                    .filter(revision -> revision.info().id().equals(revisionId))
                    .map(Revision::definition)
                    .findFirst()));
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
          history.remove(sessionId + "\0" + workflowId);
        });
  }

  @Override
  public Mono<Void> removeAll(final String sessionId) {
    return Mono.fromRunnable(
        () -> {
          store.remove(sessionId);
          history.keySet().removeIf(key -> key.startsWith(sessionId + "\0"));
        });
  }
}
