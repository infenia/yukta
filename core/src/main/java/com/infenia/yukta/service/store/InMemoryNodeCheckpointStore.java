// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory implementation of {@link NodeCheckpointStore} that records what each node produced.
 *
 * <p>Up to {@code messagesPerNode} messages and about {@code bytesPerNode} bytes of payload are
 * kept per node and execution (strings and byte arrays by size, other payloads as {@value
 * #OTHER_PAYLOAD_BYTES} bytes each). A node that produced more is marked incomplete, and a restart
 * that would need its output is refused rather than replayed partially. All state is lost on JVM
 * restart. A persistent implementation can be substituted by providing an alternative {@link
 * NodeCheckpointStore} bean.
 *
 * <p>With {@code yukta.restart.spill-dir} a node that outgrows the caps moves its whole output to a
 * {@link FileNodeCheckpointStore} in that directory (claim check) instead of being marked
 * incomplete, so a restart can still replay it; spilled messages come back as plain JSON values.
 * Spill files are removed with the execution, and leftovers of an earlier process on startup.
 */
@Slf4j
@SuppressWarnings({"PMD.AvoidSynchronizedStatement", "PMD.TooManyMethods"})
public class InMemoryNodeCheckpointStore implements NodeCheckpointStore, ByteCounted {

  /** Default number of messages kept per node and execution. */
  private static final int DEFAULT_MESSAGES_PER_NODE = 200;

  /** Default payload bytes kept per node and execution. */
  private static final long DEFAULT_BYTES_PER_NODE = 4L * 1024 * 1024;

  /** Size assumed for a payload whose size is not known cheaply. */
  private static final int OTHER_PAYLOAD_BYTES = 1024;

  /** How deep maps and collections are measured before the rest counts as unknown. */
  private static final int MAX_DEPTH = 4;

  /** How many cleared runs are remembered. */
  private static final int REMEMBERED_CLEARED = 4096;

  /** The runs cleared lately: a late message for one of them is dropped, not recorded again. */
  private final Set<String> cleared =
      Collections.newSetFromMap(
          Collections.synchronizedMap(
              new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest) {
                  return size() > REMEMBERED_CLEARED;
                }
              }));

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

  /** Where output beyond the caps goes, or null to mark such a node incomplete. */
  private final FileNodeCheckpointStore spill;

  /**
   * Creates a store with the configured caps.
   *
   * @param messagesPerNode maximum number of messages kept per node and execution
   * @param bytesPerNode maximum payload bytes kept per node and execution
   * @param spillDir directory for output beyond the caps; empty to refuse such restarts
   * @param mapper writes spilled messages
   */
  public InMemoryNodeCheckpointStore(
      final int messagesPerNode,
      final long bytesPerNode,
      final String spillDir,
      final ObjectProvider<ObjectMapper> mapper) {
    this(messagesPerNode, bytesPerNode, openSpill(spillDir, mapper));
  }

  private InMemoryNodeCheckpointStore(
      final int messagesPerNode, final long bytesPerNode, final FileNodeCheckpointStore spill) {
    this.messagesPerNode = Math.max(1, messagesPerNode);
    this.bytesPerNode = Math.max(1, bytesPerNode);
    this.spill = spill;
  }

  /**
   * Creates a store without spilling.
   *
   * @param messagesPerNode maximum number of messages kept per node and execution
   * @param bytesPerNode maximum payload bytes kept per node and execution
   */
  public InMemoryNodeCheckpointStore(final int messagesPerNode, final long bytesPerNode) {
    this(messagesPerNode, bytesPerNode, (FileNodeCheckpointStore) null);
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

  private static FileNodeCheckpointStore openSpill(
      final String spillDir, final ObjectProvider<ObjectMapper> mapper) {
    if (spillDir == null || spillDir.isBlank()) {
      return null;
    }
    final FileNodeCheckpointStore store = new FileNodeCheckpointStore(spillDir, mapper.getObject());
    removeStaleSpill(Path.of(spillDir), store);
    return store;
  }

  /** Nothing in memory survives a restart, so spill files of an earlier process are garbage. */
  private static void removeStaleSpill(final Path root, final FileNodeCheckpointStore store) {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (var children = Files.list(root)) {
      children
          .map(child -> child.toFile().getName())
          .filter(name -> name.startsWith("e-"))
          .forEach(
              name -> store.clear(URLDecoder.decode(name.substring(2), StandardCharsets.UTF_8)));
    } catch (final IOException | UncheckedIOException e) {
      log.atWarn().setCause(e).log("Could not remove old spill files");
    }
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
    return Mono.defer(
        () -> {
          if (cleared.contains(executionId)) {
            return Mono.empty();
          }
          final Recorded recorded =
              store
                  .computeIfAbsent(executionId, _ -> new ConcurrentHashMap<>())
                  .computeIfAbsent(nodeId, _ -> new Recorded());
          final long size = sizeOf(message);
          final List<Message<?>> toSpill = new ArrayList<>();
          Mono<Void> write = Mono.empty();
          synchronized (recorded) {
            if (recorded.cleared) {
              return Mono.empty();
            }
            if (recorded.spilled) {
              toSpill.add(message);
            } else if (recorded.messages.size() < messagesPerNode
                && recorded.bytes + size <= bytesPerNode) {
              recorded.messages.add(message);
              recorded.bytes += size;
              held.addAndGet(size);
            } else if (spill == null) {
              recorded.truncated = true;
            } else {
              // The whole output moves, so a replay never mixes Java objects with JSON values.
              recorded.spilled = true;
              toSpill.addAll(recorded.messages);
              toSpill.add(message);
              held.addAndGet(-recorded.bytes);
              recorded.messages.clear();
              recorded.bytes = 0;
            }
            if (!toSpill.isEmpty()) {
              write = queueSpill(recorded, executionId, nodeId, toSpill);
            }
          }
          return write;
        });
  }

  /**
   * Queues a spill write behind the node's earlier ones, so the file keeps the order of emission
   * even though each write runs on its own thread. Called with the node's lock held. The queue
   * forgets a write once it is done, so it never grows with the output.
   */
  @SuppressWarnings("PMD.CompareObjectsWithEquals") // identity: is this write still the last one
  private Mono<Void> queueSpill(
      final Recorded recorded,
      final String executionId,
      final String nodeId,
      final List<Message<?>> messages) {
    final Mono<Void> mine =
        recorded
            .spillTail
            .onErrorResume(_ -> Mono.empty())
            .then(
                Flux.fromIterable(messages)
                    .concatMap(m -> spill.save(executionId, nodeId, m))
                    .then())
            .cache();
    recorded.spillTail = mine;
    return mine.doFinally(
        _ -> {
          synchronized (recorded) {
            if (recorded.spillTail == mine) {
              recorded.spillTail = Mono.empty();
            }
          }
        });
  }

  @Override
  public Mono<Message<?>> get(final String executionId, final String nodeId) {
    return Mono.defer(
        () -> {
          final Recorded recorded = recorded(executionId, nodeId);
          if (isSpilled(recorded)) {
            return spill.get(executionId, nodeId);
          }
          final List<Message<?>> all = snapshot(executionId, nodeId);
          return all.isEmpty() ? Mono.empty() : Mono.just(all.getLast());
        });
  }

  @Override
  public Flux<Message<?>> getAll(final String executionId, final String nodeId) {
    return Flux.defer(
        () ->
            isSpilled(recorded(executionId, nodeId))
                ? spill.getAll(executionId, nodeId)
                : Flux.fromIterable(snapshot(executionId, nodeId)));
  }

  @Override
  public boolean isComplete(final String executionId, final String nodeId) {
    final Recorded recorded = recorded(executionId, nodeId);
    if (recorded == null) {
      return true;
    }
    synchronized (recorded) {
      return !recorded.truncated && (!recorded.spilled || spill.isComplete(executionId, nodeId));
    }
  }

  private static boolean isSpilled(final Recorded recorded) {
    if (recorded == null) {
      return false;
    }
    synchronized (recorded) {
      return recorded.spilled;
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
    if (spill != null) {
      spill.clear(executionId);
    }
    cleared.add(executionId);
    final Map<String, Recorded> removed = store.remove(executionId);
    if (removed != null) {
      removed
          .values()
          .forEach(
              recorded -> {
                synchronized (recorded) {
                  recorded.cleared = true;
                  held.addAndGet(-recorded.bytes);
                  recorded.bytes = 0;
                }
              });
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

    /** True once the output moved to the spill store. */
    private boolean spilled;

    /** True once the run was cleared: a message that arrives now is late and is dropped. */
    private boolean cleared;

    /** The last queued spill write, so the next one starts after it. */
    private Mono<Void> spillTail = Mono.empty();
  }
}
