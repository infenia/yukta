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
package com.infenia.yukta.service.store;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of {@link NodeCheckpointStore}.
 *
 * <p>Checkpoints are stored in a two-level {@link ConcurrentHashMap}: {@code executionId → nodeId →
 * lastMessage}. Only the most recent message per node per execution is retained, so memory growth
 * is bounded by {@code numExecutions × numNodes}.
 *
 * <p>All state is lost on JVM restart. A persistent implementation can be substituted by providing
 * an alternative {@link NodeCheckpointStore} bean.
 */
@Component
@NoArgsConstructor
public class InMemoryNodeCheckpointStore implements NodeCheckpointStore {

  /** Map of execution ID to node ID to checkpoint message. */
  private final Map<String, Map<String, Message<?>>> store = new ConcurrentHashMap<>();

  @Override
  public Mono<Void> save(final String executionId, final String nodeId, final Message<?> message) {
    return Mono.fromRunnable(
        () ->
            store
                .computeIfAbsent(executionId, k -> new ConcurrentHashMap<>())
                .put(nodeId, message));
  }

  @Override
  public Mono<Message<?>> get(final String executionId, final String nodeId) {
    return Mono.defer(
        () -> {
          final Map<String, Message<?>> nodeMap = store.get(executionId);
          if (nodeMap == null) {
            return Mono.empty();
          }
          final Message<?> msg = nodeMap.get(nodeId);
          return msg != null ? Mono.just(msg) : Mono.empty();
        });
  }

  @Override
  public void clear(final String executionId) {
    store.remove(executionId);
  }
}
