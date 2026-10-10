// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.store;

import com.infenia.yukta.message.Message;
import reactor.core.publisher.Flux;
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

  /**
   * Gets every message a node produced in an execution, in the order it produced them.
   *
   * <p>The default only knows the last message; stores that keep the full output override it.
   *
   * @param executionId the execution
   * @param nodeId the node
   * @return the recorded messages, oldest first
   */
  default Flux<Message<?>> getAll(final String executionId, final String nodeId) {
    return get(executionId, nodeId).flux();
  }

  /**
   * Whether {@link #getAll} returns everything the node produced.
   *
   * <p>A store with a size cap reports false once it had to drop messages, so a restart never
   * replays a partial output as if it were complete.
   *
   * @param executionId the execution
   * @param nodeId the node
   * @return true if nothing was dropped
   */
  default boolean isComplete(final String executionId, final String nodeId) {
    return true;
  }
}
