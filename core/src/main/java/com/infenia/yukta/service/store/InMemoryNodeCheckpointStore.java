// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of {@link NodeCheckpointStore} that records what each node produced.
 *
 * <p>Up to {@code messagesPerNode} messages and about {@code bytesPerNode} bytes of payload are
 * kept per node and execution (strings and byte arrays by size, other payloads as {@value
 * #OTHER_PAYLOAD_BYTES} bytes each). A node that produced more is marked incomplete, and a restart
 * that would need its output is refused rather than replayed partially. All state is lost on JVM
 * restart. A persistent implementation can be substituted by providing an alternative {@link
 * NodeCheckpointStore} bean.
 */
@Component
@ConditionalOnExpression("'${yukta.restart.dir:}'.isEmpty()")
@SuppressWarnings({"PMD.AvoidSynchronizedStatement", "PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
public class InMemoryNodeCheckpointStore implements NodeCheckpointStore, ByteCounted {

  /** Default number of messages kept per node and execution. */
  private static final int DEFAULT_MESSAGES_PER_NODE = 200;

  /** Default payload bytes kept per node and execution. */
  private static final long DEFAULT_BYTES_PER_NODE = 4L * 1024 * 1024;

  /** Size assumed for a payload whose size is not known cheaply. */
  private static final int OTHER_PAYLOAD_BYTES = 1024;

  /** How deep maps and collections are measured before the rest counts as unknown. */
  private static final int MAX_DEPTH = 4;

  /** Map of execution ID to node ID to recorded output. */
  private final Map<String, Map<String, Recorded>> store = new ConcurrentHashMap<>();

  /** Nodes that ended normally, as "execution\0node". */
  private final Set<String> finished = ConcurrentHashMap.newKeySet();

  /** Bytes held across all executions. */
  private final AtomicLong held = new AtomicLong();

  /** Maximum number of messages kept per node and execution. */
  private final int messagesPerNode;

  /** Maximum payload bytes kept per node and execution. */
  private final long bytesPerNode;

  /**
   * Creates a store with the configured caps.
   *
   * @param messagesPerNode maximum number of messages kept per node and execution
   * @param bytesPerNode maximum payload bytes kept per node and execution
   */
  @Autowired
  public InMemoryNodeCheckpointStore(
      @Value("${yukta.restart.messages-per-node:200}") final int messagesPerNode,
      @Value("${yukta.restart.bytes-per-node:4194304}") final long bytesPerNode) {
    this.messagesPerNode = Math.max(1, messagesPerNode);
    this.bytesPerNode = Math.max(1, bytesPerNode);
  }

  /**
   * Creates a store with the default byte cap.
   *
   * @param messagesPerNode maximum number of messages kept per node and execution
   */
  public InMemoryNodeCheckpointStore(final int messagesPerNode) {
    this(messagesPerNode, DEFAULT_BYTES_PER_NODE);
  }

  /** Creates a store with the default caps. */
  public InMemoryNodeCheckpointStore() {
    this(DEFAULT_MESSAGES_PER_NODE, DEFAULT_BYTES_PER_NODE);
  }

  private static long sizeOf(final Message<?> message) {
    return sizeOf(message.payload(), 0);
  }

  /** Estimates a payload from its contents; nesting deeper than a few levels counts as unknown. */
  private static long sizeOf(final Object value, final int depth) {
    return switch (value) {
      case CharSequence text -> text.length();
      case byte[] bytes -> bytes.length;
      case ByteBuffer buffer -> buffer.remaining();
      case Map<?, ?> map when depth < MAX_DEPTH ->
          map.entrySet().stream()
              .mapToLong(e -> sizeOf(e.getKey(), depth + 1) + sizeOf(e.getValue(), depth + 1))
              .sum();
      case Collection<?> items when depth < MAX_DEPTH ->
          items.stream().mapToLong(item -> sizeOf(item, depth + 1)).sum();
      case null, default -> OTHER_PAYLOAD_BYTES;
    };
  }

  @Override
  public Mono<Void> save(final String executionId, final String nodeId, final Message<?> message) {
    return Mono.fromRunnable(
        () -> {
          final Recorded recorded =
              store
                  .computeIfAbsent(executionId, _ -> new ConcurrentHashMap<>())
                  .computeIfAbsent(nodeId, _ -> new Recorded());
          final long size = sizeOf(message);
          synchronized (recorded) {
            if (recorded.messages.size() < messagesPerNode
                && recorded.bytes + size <= bytesPerNode) {
              recorded.messages.add(message);
              recorded.bytes += size;
              held.addAndGet(size);
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
  public Mono<Void> markComplete(final String executionId, final String nodeId) {
    return Mono.fromRunnable(() -> finished.add(executionId + "\0" + nodeId));
  }

  @Override
  public boolean isFinished(final String executionId, final String nodeId) {
    return finished.contains(executionId + "\0" + nodeId) && isComplete(executionId, nodeId);
  }

  @Override
  public void clear(final String executionId) {
    finished.removeIf(key -> key.startsWith(executionId + "\0"));
    final Map<String, Recorded> removed = store.remove(executionId);
    if (removed != null) {
      removed.values().forEach(recorded -> held.addAndGet(-recorded.bytes));
    }
  }

  @Override
  public long bytesHeld() {
    return held.get();
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

    /** Estimated payload bytes recorded. */
    private long bytes;

    /** True once a message had to be dropped. */
    private boolean truncated;
  }
}
