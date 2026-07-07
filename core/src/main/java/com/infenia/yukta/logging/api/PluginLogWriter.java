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
