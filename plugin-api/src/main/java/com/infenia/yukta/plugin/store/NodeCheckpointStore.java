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
package com.infenia.yukta.plugin.store;

import com.infenia.yukta.message.Message;
import reactor.core.publisher.Mono;

/**
 * Store for the last message produced by each node within an execution.
 *
 * <p>The orchestrator writes a checkpoint after every message a node emits. When a
 * RESTART_FROM_NODE directive is applied, the dispatcher reads the checkpoint of the restart node's
 * direct parents and replays them as synthetic input, bypassing earlier nodes.
 *
 * <p>Implementations must be thread-safe; the default in-memory implementation uses {@link
 * java.util.concurrent.ConcurrentHashMap}.
 */
public interface NodeCheckpointStore {

  /**
   * Saves (or overwrites) the last message produced by {@code nodeId} in {@code executionId}.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param message the message to store
   * @return a Mono that completes when the checkpoint is recorded
   */
  Mono<Void> save(String executionId, String nodeId, Message<?> message);

  /**
   * Retrieves the last checkpoint for {@code nodeId} in {@code executionId}.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @return a Mono emitting the stored message, or empty if none exists
   */
  Mono<Message<?>> get(String executionId, String nodeId);

  /**
   * Removes all checkpoints for the given execution, releasing memory.
   *
   * <p>Called automatically when an execution finishes (success, error, or stop).
   *
   * @param executionId the execution identifier to clear
   */
  void clear(String executionId);
}
