// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Abstraction for plugin execution log storage.
 *
 * <p>Provides storage and retrieval of execution logs with automatic retention-based cleanup.
 * Implementations are responsible for managing lifecycle: writing entries, reading them
 * chronologically, and cleaning up after retention period expires.
 *
 * <p>All operations are non-blocking reactive (Mono/Flux).
 */
public interface PluginLogStore {

  /**
   * Write a single log entry.
   *
   * <p>Non-blocking operation. Implementations should use appropriate schedulers (e.g.,
   * boundedElastic) to avoid blocking caller.
   *
   * @param entry the log entry to write
   * @return Mono that completes when entry is written
   */
  Mono<Void> write(PluginLogEntry entry);

  /**
   * Read all log entries for an execution in chronological order.
   *
   * @param executionId the execution identifier
   * @return Flux of log entries in order
   */
  Flux<PluginLogEntry> readExecution(String executionId);

  /**
   * Clean up (delete) all logs for a completed execution.
   *
   * <p>Called after execution completes and retention period elapses. Implementations may trigger
   * automatic cleanup or provide manual cleanup interface.
   *
   * @param executionId the execution identifier to clean up
   * @return Mono that completes when cleanup is done
   */
  Mono<Void> cleanup(String executionId);

  /**
   * Get the effective retention duration for logs.
   *
   * <p>Effective retention is the minimum of user-configured retention and hardcoded maximum.
   *
   * @return retention duration
   */
  Duration getEffectiveRetention();
}
