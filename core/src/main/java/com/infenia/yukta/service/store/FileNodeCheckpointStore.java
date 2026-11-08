// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
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
 * refused instead of replaying part of it. A payload comes back as its original Java type (see
 * {@link CheckpointJson}); one of a type that is not allowed comes back as plain JSON, a map for an
 * object.
 */
@Slf4j
@SuppressWarnings({
  "PMD.AvoidCatchingGenericException",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.GodClass"
})
public class FileNodeCheckpointStore implements NodeCheckpointStore, ByteCounted {

  /** Suffix of the file holding a node's messages. */
  private static final String MESSAGES = ".jsonl";

  /** Start of the first line of a messages file, which holds the encoded node id. */
  private static final String HEADER = "#node ";

  /** Suffix of the marker left when a node's output could not be recorded completely. */
  private static final String INCOMPLETE = ".incomplete";

  /** Suffix of the marker that says the node ended normally and its output is durable. */
  private static final String DONE = ".done";

  /** Log key of the run. */
  private static final String EXECUTION_ID = "executionId";

  /** Separates the run from the node in a key. */
  private static final String SEP = "\0";

  /** Run data is for this server's user only: node outputs can hold tokens and other secrets. */
  private static final FileAttribute<?>[] DIR_ATTRS =
      attributes(FileSystems.getDefault().supportedFileAttributeViews(), "rwx------");

  /** The same for the files. */
  private static final FileAttribute<?>[] FILE_ATTRS =
      attributes(FileSystems.getDefault().supportedFileAttributeViews(), "rw-------");

  /** How many cleared runs are remembered. */
  private static final int REMEMBERED_CLEARED = 4096;

  /**
   * Runs the file work of all checkpoint stores, apart from the shared {@code boundedElastic} that
   * the runs and plugins use, so checkpoint writes cannot take the threads a run needs.
   */
  private static final Scheduler IO =
      Schedulers.newBoundedElastic(
          Math.max(4, Runtime.getRuntime().availableProcessors()),
          Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
          "yukta-checkpoint",
          60,
          true);

  /** Root directory. */
  private final Path root;

  /** Nodes whose output could not be written in full: they must not get an end marker. */
  private final Set<String> failedWrites = ConcurrentHashMap.newKeySet();

  /** Bytes written and not yet cleared in this process (earlier runs' files are not counted). */
  private final AtomicLong held = new AtomicLong();

  /** Markers that could not be made durable. */
  private final AtomicLong failedMarkers = new AtomicLong();

  /** The runs cleared lately: a late message for one of them is dropped, not written again. */
  private final Set<String> cleared =
      Collections.newSetFromMap(
          Collections.synchronizedMap(
              new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest) {
                  return size() > REMEMBERED_CLEARED;
                }
              }));

  /** Reads and writes messages. */
  private final ObjectMapper mapper;

  /**
   * Creates the store.
   *
   * @param dir root directory
   * @param mapper reads and writes messages
   */
  public FileNodeCheckpointStore(final String dir, final ObjectMapper mapper) {
    this.root = Path.of(dir);
    this.mapper = mapper;
  }

  @Override
  public Mono<Void> save(final String executionId, final String nodeId, final Message<?> message) {
    return Mono.fromRunnable(() -> write(executionId, nodeId, message)).subscribeOn(IO).then();
  }

  @Override
  public Mono<Message<?>> get(final String executionId, final String nodeId) {
    return getAll(executionId, nodeId).takeLast(1).next();
  }

  @Override
  public Flux<Message<?>> getAll(final String executionId, final String nodeId) {
    return Flux.defer(() -> Flux.fromIterable(read(executionId, nodeId))).subscribeOn(IO);
  }

  @Override
  public Mono<Void> markComplete(final String executionId, final String nodeId) {
    return Mono.fromRunnable(() -> writeDone(executionId, nodeId)).subscribeOn(IO).then();
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
    if (failedWrites.contains(executionId + SEP + nodeId)) {
      // an output write failed: the file may lack a message, whatever the marker could say
      markerFailed(executionId, nodeId, null);
      return;
    }
    try {
      Files.createDirectories(dir(executionId), DIR_ATTRS);
      final Path output = file(executionId, nodeId, MESSAGES);
      if (Files.exists(output)) {
        force(output);
      }
      final Path done = file(executionId, nodeId, DONE);
      touch(done);
      force(done);
      force(dir(executionId));
      if (cleared.contains(executionId)) {
        // a late marker of a cleared run: do not leave its directory behind
        deleteTree(executionId);
      }
    } catch (final IOException | RuntimeException e) {
      markerFailed(executionId, nodeId, e);
    }
  }

  private void markerFailed(
      final String executionId, final String nodeId, final @Nullable Throwable cause) {
    failedMarkers.incrementAndGet();
    log.atError()
        .addKeyValue(EXECUTION_ID, executionId)
        .addKeyValue("nodeId", nodeId)
        .setCause(cause)
        .log("Could not make the end of a node durable, a resume will run it again");
    markIncomplete(executionId, nodeId);
  }

  /** Permissions for new files and directories, where the file system has POSIX ones. */
  /* default */ static FileAttribute<?>[] attributes(
      final Set<String> views, final String permissions) {
    return views.contains("posix")
        ? new FileAttribute<?>[] {
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
        }
        : new FileAttribute<?>[0];
  }

  /** Creates an empty marker file (readable by its owner only); one that exists is kept. */
  private static void touch(final Path path) throws IOException {
    try {
      Files.createFile(path, FILE_ATTRS);
    } catch (final FileAlreadyExistsException e) {
      log.atTrace().setCause(e).log("Marker already there");
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

  /**
   * Removes the output of every run that is not in {@code kept}: what an earlier process left for
   * runs that are no longer in the execution log would otherwise stay on disk for good.
   *
   * @param kept the ids of the runs whose output is still wanted
   */
  public void removeNotIn(final Set<String> kept) {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (var children = Files.list(root)) {
      children
          .map(child -> root.relativize(child).toString())
          .filter(name -> name.startsWith("e-"))
          .map(name -> URLDecoder.decode(name.substring(2), StandardCharsets.UTF_8))
          .filter(executionId -> !kept.contains(executionId))
          .toList()
          .forEach(this::clear);
    } catch (final IOException | UncheckedIOException e) {
      log.atWarn().setCause(e).log("Could not remove the output of runs that are gone");
    }
  }

  @Override
  public void clear(final String executionId) {
    cleared.add(executionId);
    failedWrites.removeIf(key -> key.startsWith(executionId + SEP));
    deleteTree(executionId);
  }

  private void deleteTree(final String executionId) {
    final Path dir = dir(executionId);
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        if (path.toString().endsWith(MESSAGES)) {
          final long size = Files.size(path);
          held.updateAndGet(h -> Math.max(0, h - size));
        }
        Files.deleteIfExists(path);
      }
    } catch (final IOException | UncheckedIOException e) {
      log.atWarn().addKeyValue(EXECUTION_ID, executionId).setCause(e).log("Could not clear");
    }
  }

  @Override
  public long bytesHeld() {
    return held.get();
  }

  private void write(final String executionId, final String nodeId, final Message<?> message) {
    try {
      final String json = CheckpointJson.write(mapper, message) + "\n";
      Files.createDirectories(dir(executionId), DIR_ATTRS);
      final long written;
      // Opened per message and closed at once: open descriptors must not grow with runs x nodes.
      try (var channel =
          FileChannel.open(
              file(executionId, nodeId, MESSAGES),
              Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND),
              FILE_ATTRS)) {
        // The file name is a hash, so the first write leaves the id in a header line (a racing
        // second header is harmless: readers skip every header).
        final byte[] line =
            ((channel.size() == 0 ? HEADER + encode(nodeId) + "\n" : "") + json)
                .getBytes(StandardCharsets.UTF_8);
        // One write call per line on an O_APPEND channel: lines of concurrent writers stay whole.
        final ByteBuffer buffer = ByteBuffer.wrap(line);
        written = line.length;
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
      }
      if (cleared.contains(executionId)) {
        // Checked after the write: a clear that ran in between has already set the flag, so a late
        // message cannot leave a directory behind that no later clear removes.
        log.atDebug()
            .addKeyValue(EXECUTION_ID, executionId)
            .addKeyValue("nodeId", nodeId)
            .log("Dropped a late node output: the run was cleared");
        deleteTree(executionId);
        return;
      }
      held.addAndGet(written);
    } catch (final IOException | RuntimeException e) {
      failedWrites.add(executionId + SEP + nodeId);
      log.atWarn()
          .addKeyValue(EXECUTION_ID, executionId)
          .addKeyValue("nodeId", nodeId)
          .setCause(e)
          .log("Could not record node output, the node cannot be restarted from");
      markIncomplete(executionId, nodeId);
    }
  }

  private void markIncomplete(final String executionId, final String nodeId) {
    try {
      Files.createDirectories(dir(executionId), DIR_ATTRS);
      touch(file(executionId, nodeId, INCOMPLETE));
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
        if (!line.isBlank() && !line.startsWith(HEADER)) {
          messages.add(CheckpointJson.read(mapper, line));
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
    return dir(executionId).resolve(fileName(nodeId) + suffix);
  }

  /** Hash of the id: a fixed length, and distinct on case-insensitive file systems (hex). */
  /* default */ static String fileName(final String nodeId) {
    return hash("SHA-256", nodeId);
  }

  /** The hex of the hash of a text; every JVM has SHA-256, so that name never fails. */
  /* default */ static String hash(final String algorithm, final String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Ids come from user definitions, so they never become path segments as they are. */
  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
