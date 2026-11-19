// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Append-only record of execution lifecycle events, kept so runs survive a restart of the server.
 *
 * <p>Appending must never fail a run: implementations log and swallow storage errors.
 */
public interface ExecutionLogStore {

  /**
   * Appends an event.
   *
   * @param event the event
   */
  void append(ExecutionLogEvent event);

  /**
   * Reads every stored event, oldest first, as seen when it is subscribed: every event appended
   * before that is in it. Unreadable entries are skipped.
   *
   * @return the events
   */
  Flux<ExecutionLogEvent> readAll();

  /**
   * Replaces the whole log, used to drop old runs after recovery.
   *
   * @param events the events to keep, oldest first
   * @return completes when the log has been replaced
   */
  Mono<Void> replaceAll(List<ExecutionLogEvent> events);

  /**
   * Reads the events of one run, oldest first. The default filters {@link #readAll()}; stores
   * override it with an indexed lookup, so hot paths never load the whole log.
   *
   * @param executionId the run
   * @return its events, empty if unknown
   */
  default Flux<ExecutionLogEvent> forExecution(final String executionId) {
    return readAll().filter(event -> executionId.equals(event.executionId()));
  }

  /**
   * Who owns a run, for authorisation and search.
   *
   * @param executionId the run
   * @return its header, empty if the log has no start for it
   */
  default Mono<RunHeader> header(final String executionId) {
    return forExecution(executionId)
        .filter(event -> event.type() == ExecutionLogEvent.Type.RUN_STARTED)
        .next()
        .map(event -> new RunHeader(event.sessionId(), event.workflowId()));
  }

  /**
   * Reads the events of one type, oldest first.
   *
   * @param type the type
   * @param since only events after this instant
   * @return the events
   */
  default Flux<ExecutionLogEvent> ofType(final ExecutionLogEvent.Type type, final Instant since) {
    return readAll().filter(event -> event.type() == type && event.timestamp().isAfter(since));
  }

  /**
   * The newest events of the given types, newest first, at most {@code limit}. The default scans
   * {@link #readAll()}; stores override it with a bounded query.
   *
   * @param types the event types wanted
   * @param executionId only this run's events, or null for all
   * @param limit how many events at most
   * @return the events, newest first
   */
  default Flux<ExecutionLogEvent> newest(
      final Set<ExecutionLogEvent.Type> types, final String executionId, final int limit) {
    return readAll()
        .collectList()
        .flatMapMany(events -> Flux.fromIterable(events.reversed()))
        .filter(event -> matches(event, types, executionId))
        .take(limit);
  }

  /**
   * Whether an event is of one of the types and, if given, of the run.
   *
   * @param event the event
   * @param types the types wanted
   * @param executionId the run, or null
   * @return true if it matches
   */
  static boolean matches(
      final ExecutionLogEvent event,
      final Set<ExecutionLogEvent.Type> types,
      final String executionId) {
    return types.contains(event.type())
        && (executionId == null || executionId.equals(event.executionId()));
  }

  /**
   * The owner of a run.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   */
  record RunHeader(String sessionId, String workflowId) {}
}
