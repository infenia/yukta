// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link NodeCheckpointStore} that keeps what each node produced on disk, so a run that was
 * interrupted by a server restart can be resumed.
 *
 * <p>Registered as the durable store by the Plus edition when {@code yukta.restart.dir} is set; the
 * in-memory store also uses it for output beyond its caps. One JSON line per message, in {@code
 * <dir>/e-<executionId>/<nodeId>.jsonl}. Output is not capped (the disk is the claim check); a
 * message that cannot be written as JSON marks the node incomplete, so a restart that needs it is
 * refused instead of replaying part of it. Messages come back as plain JSON values: a payload that
 * was a Java object is read as a map, and bytes as base64 text.
 */
@Slf4j
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidCatchingGenericException", "PMD.TooManyMethods"})
public class FileNodeCheckpointStore implements NodeCheckpointStore, ByteCounted {

  /** Suffix of the file holding a node's messages. */
  private static final String MESSAGES = ".jsonl";

  /** Suffix of the marker left when a node's output could not be recorded completely. */
  private static final String INCOMPLETE = ".incomplete";

  /** Suffix of the marker that says the node ended normally and its output is durable. */
  private static final String DONE = ".done";

  /** Root directory. */
  private final Path root;

  /** Bytes written and not yet cleared in this process (earlier runs' files are not counted). */
  private final AtomicLong held = new AtomicLong();

  /** Markers that could not be made durable. */
  private final AtomicLong failedMarkers = new AtomicLong();

  /** Open output files by run and node, so a message costs one write and not an open. */
  private final Map<String, FileChannel> appenders = new ConcurrentHashMap<>();

  /** Reads and writes messages. */
  private final ObjectMapper mapper;

  /**
   * Creates the store.
   *
   * @param dir root directory
   * @param mapper reads and writes messages
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public FileNodeCheckpointStore(final String dir, final ObjectMapper mapper) {
    this.root = Path.of(dir);
    this.mapper = mapper;
  }

  @Override
  public Mono<Void> save(final String executionId, final String nodeId, final Message<?> message) {
    return Mono.fromRunnable(() -> write(executionId, nodeId, message))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @Override
  public Mono<Message<?>> get(final String executionId, final String nodeId) {
    return getAll(executionId, nodeId).takeLast(1).next();
  }

  @Override
  public Flux<Message<?>> getAll(final String executionId, final String nodeId) {
    return Flux.defer(() -> Flux.fromIterable(read(executionId, nodeId)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<Void> markComplete(final String executionId, final String nodeId) {
    return Mono.fromRunnable(() -> writeDone(executionId, nodeId))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @Override
  public boolean isFinished(final String executionId, final String nodeId) {
    return Files.exists(file(executionId, nodeId, DONE)) && isComplete(executionId, nodeId);
  }

  @Override
  public long markerFailures() {
    return failedMarkers.get();
  }

  /**
   * Forces the node's output to disk, then writes and forces the marker and the directory entry. If
   * anything fails the node is marked incomplete, so a resume runs it again instead of trusting a
   * marker that may not survive a crash.
   */
  private void writeDone(final String executionId, final String nodeId) {
    try {
      Files.createDirectories(dir(executionId));
      final Path output = file(executionId, nodeId, MESSAGES);
      closeAppender(executionId, nodeId);
      if (Files.exists(output)) {
        force(output);
      }
      final Path done = file(executionId, nodeId, DONE);
      Files.writeString(done, "");
      force(done);
      force(dir(executionId));
    } catch (final IOException | RuntimeException e) {
      failedMarkers.incrementAndGet();
      log.atError()
          .addKeyValue("executionId", executionId)
          .addKeyValue("nodeId", nodeId)
          .setCause(e)
          .log("Could not make the end of a node durable, a resume will run it again");
      markIncomplete(executionId, nodeId);
    }
  }

  private static void force(final Path path) throws IOException {
    final var options =
        Files.isDirectory(path)
            ? new StandardOpenOption[] {StandardOpenOption.READ}
            : new StandardOpenOption[] {StandardOpenOption.WRITE};
    try (var channel = FileChannel.open(path, options)) {
      channel.force(true);
    }
  }

  @Override
  public boolean isComplete(final String executionId, final String nodeId) {
    return !Files.exists(file(executionId, nodeId, INCOMPLETE))
        && endsWithNewline(executionId, nodeId);
  }

  /** A write that was cut short by a crash leaves a last line without its newline. */
  private boolean endsWithNewline(final String executionId, final String nodeId) {
    final Path file = file(executionId, nodeId, MESSAGES);
    if (!Files.exists(file)) {
      return true;
    }
    try (var tail = new java.io.RandomAccessFile(file.toFile(), "r")) {
      final long length = tail.length();
      if (length == 0) {
        return true;
      }
      tail.seek(length - 1);
      return tail.read() == '\n';
    } catch (final IOException e) {
      return false;
    }
  }

  @Override
  public void clear(final String executionId) {
    appenders
        .keySet()
        .removeIf(
            key -> {
              if (key.startsWith(executionId + "\0")) {
                closeQuietly(appenders.get(key));
                return true;
              }
              return false;
            });
    final Path dir = dir(executionId);
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        if (path.toString().endsWith(MESSAGES)) {
          held.addAndGet(-Math.min(held.get(), Files.size(path)));
        }
        Files.deleteIfExists(path);
      }
    } catch (final IOException | UncheckedIOException e) {
      log.atWarn().addKeyValue("executionId", executionId).setCause(e).log("Could not clear");
    }
  }

  @Override
  public long bytesHeld() {
    return held.get();
  }

  /** The open output file of a node, kept until the node completes or the run is cleared. */
  private FileChannel appender(final String executionId, final String nodeId) {
    return appenders.compute(
        executionId + "\0" + nodeId,
        (_, open) -> {
          if (open != null && open.isOpen()) {
            return open;
          }
          try {
            Files.createDirectories(dir(executionId));
            return FileChannel.open(
                file(executionId, nodeId, MESSAGES),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
          } catch (final IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  /** Closes the open output file of a node, if any. */
  private void closeAppender(final String executionId, final String nodeId) {
    closeQuietly(appenders.remove(executionId + "\0" + nodeId));
  }

  private static void closeQuietly(final FileChannel channel) {
    if (channel != null) {
      try {
        channel.close();
      } catch (final IOException e) {
        log.atDebug().setCause(e).log("Could not close a node output file");
      }
    }
  }

  @SuppressWarnings("PMD.CloseResource") // the appender is closed by markComplete or clear
  private void write(final String executionId, final String nodeId, final Message<?> message) {
    try {
      final byte[] line =
          (mapper.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
      // One write call per line on an O_APPEND channel: lines of concurrent writers stay whole.
      final ByteBuffer buffer = ByteBuffer.wrap(line);
      final FileChannel channel = appender(executionId, nodeId);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      held.addAndGet(line.length);
    } catch (final IOException | RuntimeException e) {
      closeAppender(executionId, nodeId);
      log.atWarn()
          .addKeyValue("executionId", executionId)
          .addKeyValue("nodeId", nodeId)
          .setCause(e)
          .log("Could not record node output, the node cannot be restarted from");
      markIncomplete(executionId, nodeId);
    }
  }

  private void markIncomplete(final String executionId, final String nodeId) {
    try {
      Files.createDirectories(dir(executionId));
      Files.writeString(file(executionId, nodeId, INCOMPLETE), "");
    } catch (final IOException e) {
      log.atError().setCause(e).log("Could not mark node output incomplete");
    }
  }

  private List<Message<?>> read(final String executionId, final String nodeId) {
    final Path file = file(executionId, nodeId, MESSAGES);
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      final List<Message<?>> messages = new ArrayList<>();
      for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          messages.add(mapper.readValue(line, DefaultMessage.class));
        }
      }
      return messages;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } catch (final RuntimeException e) {
      throw new UncheckedIOException(
          new IOException("Unreadable output of node " + nodeId + " in " + executionId, e));
    }
  }

  private Path dir(final String executionId) {
    return root.resolve("e-" + encode(executionId));
  }

  private Path file(final String executionId, final String nodeId, final String suffix) {
    return dir(executionId).resolve(encode(nodeId) + suffix);
  }

  /** Ids come from user definitions, so they never become path segments as they are. */
  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
