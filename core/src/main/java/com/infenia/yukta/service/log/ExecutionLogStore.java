// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
   * Reads every stored event, oldest first. Unreadable entries are skipped.
   *
   * @return the events
   */
  List<ExecutionLogEvent> readAll();

  /**
   * Replaces the whole log, used to drop old runs after recovery.
   *
   * @param events the events to keep, oldest first
   */
  void replaceAll(List<ExecutionLogEvent> events);

  /**
   * Reads the events of one run, oldest first. The default filters {@link #readAll()}; stores
   * override it with an indexed lookup, so hot paths never load the whole log.
   *
   * @param executionId the run
   * @return its events, empty if unknown
   */
  default List<ExecutionLogEvent> forExecution(final String executionId) {
    return readAll().stream().filter(event -> executionId.equals(event.executionId())).toList();
  }

  /**
   * Who owns a run, for authorisation and search.
   *
   * @param executionId the run
   * @return its header, empty if the log has no start for it
   */
  default Optional<RunHeader> header(final String executionId) {
    return forExecution(executionId).stream()
        .filter(event -> event.type() == ExecutionLogEvent.Type.RUN_STARTED)
        .findFirst()
        .map(event -> new RunHeader(event.sessionId(), event.workflowId()));
  }

  /**
   * Reads the events of one type, oldest first.
   *
   * @param type the type
   * @param since only events after this instant
   * @return the events
   */
  default List<ExecutionLogEvent> ofType(final ExecutionLogEvent.Type type, final Instant since) {
    return readAll().stream()
        .filter(event -> event.type() == type && event.timestamp().isAfter(since))
        .toList();
  }

  /**
   * The owner of a run.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   */
  record RunHeader(String sessionId, String workflowId) {}
}
