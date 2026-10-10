// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of {@link NodeCheckpointStore} that records what each node produced.
 *
 * <p>Up to {@code messagesPerNode} messages are kept per node and execution. A node that produced
 * more is marked incomplete, and a restart that would need its output is refused rather than
 * replayed partially. All state is lost on JVM restart. A persistent implementation can be
 * substituted by providing an alternative {@link NodeCheckpointStore} bean.
 */
@Component
@SuppressWarnings({"PMD.AvoidSynchronizedStatement", "PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
public class InMemoryNodeCheckpointStore implements NodeCheckpointStore {

  /** Default number of messages kept per node and execution. */
  private static final int DEFAULT_MESSAGES_PER_NODE = 200;

  /** Map of execution ID to node ID to recorded output. */
  private final Map<String, Map<String, Recorded>> store = new ConcurrentHashMap<>();

  /** Maximum number of messages kept per node and execution. */
  private final int messagesPerNode;

  /**
   * Creates a store with the configured cap.
   *
   * @param messagesPerNode maximum number of messages kept per node and execution
   */
  @Autowired
  public InMemoryNodeCheckpointStore(
      @Value("${yukta.restart.messages-per-node:200}") final int messagesPerNode) {
    this.messagesPerNode = Math.max(1, messagesPerNode);
  }

  /** Creates a store with the default cap. */
  public InMemoryNodeCheckpointStore() {
    this(DEFAULT_MESSAGES_PER_NODE);
  }

  @Override
  public Mono<Void> save(final String executionId, final String nodeId, final Message<?> message) {
    return Mono.fromRunnable(
        () -> {
          final Recorded recorded =
              store
                  .computeIfAbsent(executionId, _ -> new ConcurrentHashMap<>())
                  .computeIfAbsent(nodeId, _ -> new Recorded());
          synchronized (recorded) {
            if (recorded.messages.size() < messagesPerNode) {
              recorded.messages.add(message);
            } else {
              recorded.truncated = true;
            }
          }
        });
  }

  @Override
  public Mono<Message<?>> get(final String executionId, final String nodeId) {
    return Mono.defer(
        () -> {
          final List<Message<?>> all = snapshot(executionId, nodeId);
          return all.isEmpty() ? Mono.empty() : Mono.just(all.getLast());
        });
  }

  @Override
  public Flux<Message<?>> getAll(final String executionId, final String nodeId) {
    return Flux.defer(() -> Flux.fromIterable(snapshot(executionId, nodeId)));
  }

  @Override
  public boolean isComplete(final String executionId, final String nodeId) {
    final Recorded recorded = recorded(executionId, nodeId);
    if (recorded == null) {
      return true;
    }
    synchronized (recorded) {
      return !recorded.truncated;
    }
  }

  @Override
  public void clear(final String executionId) {
    store.remove(executionId);
  }

  private Recorded recorded(final String executionId, final String nodeId) {
    final Map<String, Recorded> nodes = store.get(executionId);
    return nodes == null ? null : nodes.get(nodeId);
  }

  private List<Message<?>> snapshot(final String executionId, final String nodeId) {
    final Recorded recorded = recorded(executionId, nodeId);
    if (recorded == null) {
      return List.of();
    }
    synchronized (recorded) {
      return List.copyOf(recorded.messages);
    }
  }

  /** What one node produced in one execution. */
  private static final class Recorded {
    /** The recorded messages, oldest first. */
    private final List<Message<?>> messages = new ArrayList<>();

    /** True once a message had to be dropped. */
    private boolean truncated;
  }
}
