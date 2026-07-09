// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for writing plugin log entries to a backend storage.
 *
 * <p>Implementations should handle non-blocking persistence of logs with appropriate error handling
 * and resource management.
 */
public interface PluginLogWriter {

  /**
   * Write a single log entry.
   *
   * @param entry the log entry to write
   * @return a Mono that completes when the entry is written
   */
  Mono<Void> write(PluginLogEntry entry);

  /**
   * Write multiple log entries in a batch.
   *
   * <p>Implementations should optimize for batch persistence.
   *
   * @param entries the log entries to write
   * @return a Mono that completes when all entries are written
   */
  Mono<Void> writeBatch(List<PluginLogEntry> entries);

  /**
   * Close the writer and release resources.
   *
   * @return a Mono that completes when the writer is closed
   */
  Mono<Void> close();
}
