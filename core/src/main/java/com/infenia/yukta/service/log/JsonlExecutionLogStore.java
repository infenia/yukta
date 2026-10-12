// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the execution log in a JSON-lines file, one event per line.
 *
 * <p>All file access runs on one background thread, so callers (including reactive and event-loop
 * threads) never block on disk. {@link #append} only queues the event; {@link #readAll} and {@link
 * #replaceAll} wait for the queued events first, so they always see them. When the file has taken
 * {@code compact-after} events it is rewritten to keep only the newest {@code retained-runs} runs,
 * so it cannot grow without bound.
 *
 * <p>Durability: events are handed to the operating system but not {@code fsync}ed, so a power loss
 * (not a process crash) can lose the last few events. At most {@value #QUEUE_CAPACITY} events wait
 * for the writer; further ones are dropped with an error log.
 */
@Slf4j
@Component
@ConditionalOnExpression(
    "!'${yukta.execution-log.dir:}'.isEmpty()"
        + " && !'postgres'.equals('${yukta.session.store-type:in-memory}')")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn", "PMD.TooManyMethods"})
public class JsonlExecutionLogStore implements ExecutionLogStore {

  /** Name of the log file inside the directory. */
  private static final String FILE_NAME = "executions.jsonl";

  /** Events that may wait for the writer. */
  private static final int QUEUE_CAPACITY = 10_000;

  /** How long to wait for queued events on shutdown, in milliseconds. */
  private static final long SHUTDOWN_WAIT_MILLIS = 5_000;

  /** Directory that holds the log. */
  private final Path dir;

  /** The log file. */
  private final Path file;

  /** Serialises events. */
  private final ObjectMapper mapper;

  /** Runs that survive a compaction. */
  private final int retainedRuns;

  /** Appended events after which the file is compacted. */
  private final int compactAfter;

  /** How long {@link #close()} waits for queued events, in milliseconds. */
  private final long shutdownWaitMillis;

  /** The one thread that touches the file. */
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          task -> {
            final Thread thread = new Thread(task, "execution-log-writer");
            thread.setDaemon(true);
            return thread;
          });

  /** Events appended since the last compaction; only touched on the writer thread. */
  private int sinceCompaction;

  /**
   * Creates the store.
   *
   * @param dir directory of the log file
   * @param mapper serialises events
   * @param retainedRuns runs kept when the file is compacted
   * @param compactAfter appended events after which the file is compacted
   */
  @Autowired
  public JsonlExecutionLogStore(
      @Value("${yukta.execution-log.dir}") final String dir,
      final ObjectMapper mapper,
      @Value("${yukta.execution-log.retained-runs:500}") final int retainedRuns,
      @Value("${yukta.execution-log.compact-after:10000}") final int compactAfter) {
    this(dir, mapper, retainedRuns, compactAfter, SHUTDOWN_WAIT_MILLIS);
  }

  /* default */ JsonlExecutionLogStore(
      final String dir,
      final ObjectMapper mapper,
      final int retainedRuns,
      final int compactAfter,
      final long shutdownWaitMillis) {
    this.dir = Path.of(dir);
    this.file = this.dir.resolve(FILE_NAME);
    this.mapper = mapper;
    this.retainedRuns = Math.max(1, retainedRuns);
    this.compactAfter = Math.max(1, compactAfter);
    this.shutdownWaitMillis = shutdownWaitMillis;
  }

  /* default */ JsonlExecutionLogStore(final String dir, final ObjectMapper mapper) {
    this(dir, mapper, 500, 10_000);
  }

  @Override
  public void append(final ExecutionLogEvent event) {
    try {
      writer.execute(() -> write(event));
    } catch (final RejectedExecutionException e) {
      log.atError()
          .addKeyValue("executionId", event.executionId())
          .log("Execution log is falling behind, event dropped");
    }
  }

  @Override
  public List<ExecutionLogEvent> readAll() {
    return onWriter(this::read, List.of());
  }

  @Override
  public void replaceAll(final List<ExecutionLogEvent> events) {
    onWriter(
        () -> {
          rewrite(events);
          return null;
        },
        null);
  }

  /** Waits for queued events, stops the writer, and does not accept more. */
  @PreDestroy
  public void close() {
    writer.shutdown();
    try {
      if (!writer.awaitTermination(shutdownWaitMillis, TimeUnit.MILLISECONDS)) {
        log.atWarn().log("Execution log writer did not finish in time");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Runs {@code work} after everything queued so far, and returns {@code fallback} on failure. */
  private <T> T onWriter(final Callable<T> work, final T fallback) {
    try {
      return writer.submit(work).get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return fallback;
    } catch (final ExecutionException | RejectedExecutionException e) {
      log.atError().setCause(e).log("Could not access the execution log");
      return fallback;
    }
  }

  private void write(final ExecutionLogEvent event) {
    try {
      Files.createDirectories(dir);
      Files.writeString(
          file,
          json(event) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (final IOException | RuntimeException e) {
      log.atError()
          .addKeyValue("executionId", event.executionId())
          .setCause(e)
          .log("Could not append to the execution log");
    }
    sinceCompaction++;
    if (sinceCompaction >= compactAfter) {
      sinceCompaction = 0;
      compact();
    }
  }

  /** Serialises an event; a payload that cannot be written is left out, not the whole event. */
  private String json(final ExecutionLogEvent event) {
    try {
      return mapper.writeValueAsString(event);
    } catch (final RuntimeException e) {
      if (event.payload() == null) {
        throw e;
      }
      log.atWarn()
          .addKeyValue("executionId", event.executionId())
          .log("Run payload cannot be serialised, so the run cannot be resumed");
      return mapper.writeValueAsString(event.withoutPayload());
    }
  }

  /** Drops the events of all but the newest runs. */
  private void compact() {
    final List<ExecutionLogEvent> events = read();
    // Newest by last activity, so a long run that is still going is not "old"; a run without an
    // end is never dropped.
    final List<String> runs = runsByLastActivity(events);
    if (runs.size() <= retainedRuns) {
      return;
    }
    final Set<String> keep =
        new LinkedHashSet<>(runs.subList(runs.size() - retainedRuns, runs.size()));
    keep.addAll(openRuns(events));
    // scheduler changes belong to no run: keep the newest few
    final List<ExecutionLogEvent> scheduler =
        events.stream().filter(e -> ExecutionLogEvent.SCHEDULER.equals(e.executionId())).toList();
    final Set<ExecutionLogEvent> keepScheduler =
        Set.copyOf(
            scheduler.subList(Math.max(0, scheduler.size() - retainedRuns), scheduler.size()));
    rewrite(
        events.stream()
            .filter(event -> keep.contains(event.executionId()) || keepScheduler.contains(event))
            .toList());
    log.atInfo()
        .addKeyValue("dropped", runs.size() - keep.size())
        .log("Compacted the execution log");
  }

  /** Run ids, the one with the oldest last event first; scheduler changes are not runs. */
  private static List<String> runsByLastActivity(final List<ExecutionLogEvent> events) {
    final Set<String> runs = new LinkedHashSet<>();
    for (final ExecutionLogEvent event : events) {
      if (!ExecutionLogEvent.SCHEDULER.equals(event.executionId())) {
        runs.remove(event.executionId());
        runs.add(event.executionId());
      }
    }
    return new ArrayList<>(runs);
  }

  /** Runs that started and have neither finished nor been marked aborted. */
  private static Set<String> openRuns(final List<ExecutionLogEvent> events) {
    final Set<String> open = new LinkedHashSet<>();
    for (final ExecutionLogEvent event : events) {
      switch (event.type()) {
        case RUN_STARTED -> open.add(event.executionId());
        case RUN_FINISHED, RUN_ABORTED -> open.remove(event.executionId());
        default -> {
          // not a start or an end
        }
      }
    }
    return open;
  }

  private List<ExecutionLogEvent> read() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    if (!Files.exists(file)) {
      return events;
    }
    try {
      for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        parse(line, events);
      }
    } catch (final IOException e) {
      log.atError().setCause(e).log("Could not read the execution log");
    }
    return events;
  }

  private void parse(final String line, final List<ExecutionLogEvent> events) {
    if (line.isBlank()) {
      return;
    }
    try {
      events.add(mapper.readValue(line, ExecutionLogEvent.class));
    } catch (final RuntimeException e) {
      log.atWarn().setCause(e).log("Skipping an unreadable execution log line");
    }
  }

  private void rewrite(final List<ExecutionLogEvent> events) {
    try {
      Files.createDirectories(dir);
      final Path tmp = Files.createTempFile(dir, "executions", ".tmp");
      try {
        final StringBuilder content = new StringBuilder();
        for (final ExecutionLogEvent event : events) {
          content.append(json(event)).append('\n');
        }
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        // On disk before it replaces the log, or a power loss could leave an empty history.
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
          channel.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (final IOException | RuntimeException e) {
      log.atError().setCause(e).log("Could not rewrite the execution log");
    }
  }
}
