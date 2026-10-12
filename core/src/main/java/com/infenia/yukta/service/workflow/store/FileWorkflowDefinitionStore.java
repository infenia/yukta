// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps workflow definitions on disk, so they survive a restart, together with the newest {@value
 * #MAX_REVISIONS} saved versions of each. Used when {@code yukta.session.store-type} is {@code
 * file}.
 *
 * <p>Layout under {@code <baseDir>/workflows/<session>/<workflow>/}: {@code current.json} and
 * {@code history/<version>.json}. Session and workflow ids are URL-encoded, so they cannot name a
 * path outside the directory. Every file is written beside its target, forced to disk and moved
 * into place.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yukta.session.store-type", havingValue = "file")
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseConcurrentHashMap", "PMD.GuardLogStatement"})
public class FileWorkflowDefinitionStore implements WorkflowDefinitionStore {

  /** Most versions kept per workflow. */
  public static final int MAX_REVISIONS = 20;

  /** Name of the file with the current definition. */
  private static final String CURRENT = "current.json";

  /** Name of the directory with the saved versions. */
  private static final String HISTORY = "history";

  /** Where the files live. */
  private final SessionConfigProperties props;

  /** Reads and writes the definitions. */
  private final ObjectMapper mapper;

  /** Serialises writes; definitions change rarely. */
  private final ReentrantLock lock = new ReentrantLock();

  @PostConstruct
  /* default */ void logInitialization() {
    log.info("Using WorkflowDefinitionStore with type: file ({})", root());
  }

  @Override
  public Mono<Void> save(final String sessionId, final WorkflowDefinition definition) {
    return Mono.fromCallable(
            () -> {
              lock.lock();
              try {
                write(sessionId, definition);
              } finally {
                lock.unlock();
              }
              return definition;
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @Override
  public Mono<WorkflowDefinition> find(final String sessionId, final String workflowId) {
    return Mono.fromCallable(() -> read(dir(sessionId, workflowId).resolve(CURRENT)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<Map<String, WorkflowDefinition>> findAll(final String sessionId) {
    return Mono.fromCallable(
            () -> {
              final Map<String, WorkflowDefinition> all = new HashMap<>();
              final Path session = sessionDir(sessionId);
              if (Files.isDirectory(session)) {
                try (Stream<Path> workflows = Files.list(session)) {
                  for (final Path workflow : workflows.toList()) {
                    final WorkflowDefinition definition = read(workflow.resolve(CURRENT));
                    if (definition != null) {
                      all.put(definition.workflowId(), definition);
                    }
                  }
                }
              }
              return Map.copyOf(all);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<Void> remove(final String sessionId, final String workflowId) {
    return Mono.fromCallable(() -> delete(dir(sessionId, workflowId)))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @Override
  public Mono<Void> removeAll(final String sessionId) {
    return Mono.fromCallable(() -> delete(sessionDir(sessionId)))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @Override
  public Flux<DefinitionRevision> revisions(final String sessionId, final String workflowId) {
    return Mono.fromCallable(() -> listRevisions(dir(sessionId, workflowId)))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapIterable(revisions -> revisions);
  }

  @Override
  public Mono<WorkflowDefinition> findRevision(
      final String sessionId, final String workflowId, final String revisionId) {
    return Mono.fromCallable(
            () ->
                read(
                    inside(
                        dir(sessionId, workflowId).resolve(HISTORY).resolve(fileOf(revisionId)))))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void write(final String sessionId, final WorkflowDefinition definition)
      throws IOException {
    final Path dir = dir(sessionId, definition.workflowId());
    if (definition.equals(read(dir.resolve(CURRENT)))) {
      return;
    }
    final Instant now = Instant.now();
    final String json = mapper.writeValueAsString(definition);
    Files.createDirectories(dir.resolve(HISTORY));
    atomically(dir.resolve(HISTORY), fileOf(DefinitionRevisions.idOf(now, definition)), json);
    atomically(dir, CURRENT, json);
    prune(dir.resolve(HISTORY));
  }

  private List<DefinitionRevision> listRevisions(final Path dir) throws IOException {
    final Path history = dir.resolve(HISTORY);
    List<DefinitionRevision> revisions = List.of();
    if (Files.isDirectory(history)) {
      try (Stream<Path> files = Files.list(history)) {
        revisions =
            files
                .map(file -> history.relativize(file).toString())
                .filter(name -> name.endsWith(".json"))
                .map(name -> name.substring(0, name.length() - ".json".length()))
                .filter(FileWorkflowDefinitionStore::isRevisionId)
                .sorted(Comparator.reverseOrder())
                .map(
                    id ->
                        new DefinitionRevision(
                            id, Instant.ofEpochMilli(Long.parseLong(id.substring(0, 13)))))
                .toList();
      }
    }
    return revisions;
  }

  private void prune(final Path history) throws IOException {
    final List<Path> files;
    try (Stream<Path> listed = Files.list(history)) {
      files = listed.sorted(Comparator.comparing(Path::toString)).toList();
    }
    for (int i = 0; i < files.size() - MAX_REVISIONS; i++) {
      Files.deleteIfExists(files.get(i));
    }
  }

  private WorkflowDefinition read(final Path file) throws IOException {
    WorkflowDefinition definition = null;
    if (Files.exists(file)) {
      definition =
          mapper.readValue(
              Files.readString(file, StandardCharsets.UTF_8), WorkflowDefinition.class);
    }
    return definition;
  }

  private static void atomically(final Path dir, final String name, final String content)
      throws IOException {
    final Path tmp = Files.createTempFile(dir, "definition", ".tmp");
    try {
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(
          tmp,
          dir.resolve(name),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static Path delete(final Path path) throws IOException {
    if (Files.exists(path)) {
      try (Stream<Path> tree = Files.walk(path)) {
        for (final Path entry : tree.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(entry);
        }
      }
    }
    return path;
  }

  private Path root() {
    return Path.of(props.getBaseDir()).resolve("workflows").toAbsolutePath().normalize();
  }

  private Path sessionDir(final String sessionId) {
    return inside(root().resolve(encode(sessionId)));
  }

  private Path dir(final String sessionId, final String workflowId) {
    return inside(sessionDir(sessionId).resolve(encode(workflowId)));
  }

  private Path inside(final Path path) {
    return inside(root(), path);
  }

  /**
   * Returns the normalised path, or refuses it unless it lies strictly beneath the root. Ids are
   * encoded and refused when they are dots, so this should never trigger; it keeps the store from
   * reading, writing or deleting anything outside its directory should that ever change.
   */
  /* default */ static Path inside(final Path root, final Path path) {
    final Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(root) || normalized.equals(root)) {
      throw new IllegalArgumentException("Path is outside the workflows directory: " + path);
    }
    return normalized;
  }

  /**
   * Ids become file names, so anything that could name another path is encoded. URL encoding leaves
   * dots alone, so the names that would walk up a directory are refused outright.
   */
  private static String encode(final String value) {
    final String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
    if (encoded.isEmpty() || ".".equals(encoded) || "..".equals(encoded)) {
      throw new IllegalArgumentException("Not a usable id: '" + value + "'");
    }
    return encoded;
  }

  /** A revision id starts with its 13-digit timestamp; other files in history are skipped. */
  private static boolean isRevisionId(final String name) {
    return name.length() >= 13 && name.substring(0, 13).chars().allMatch(Character::isDigit);
  }

  private static String fileOf(final String revisionId) {
    return encode(revisionId) + ".json";
  }
}
