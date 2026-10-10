// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.util.List;

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
}
