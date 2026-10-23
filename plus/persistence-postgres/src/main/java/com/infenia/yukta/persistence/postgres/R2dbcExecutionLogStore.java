// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the execution log in PostgreSQL.
 *
 * <p>The store interface is blocking, so all database access runs on one background thread, like
 * the file store: {@link #append} only queues the event; {@link #readAll} and {@link #replaceAll}
 * wait for the queued events first. After {@code compact-after} events the log is trimmed to the
 * newest {@code retained-runs} runs; a run without an end is never dropped.
 */
@Slf4j
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn", "PMD.TooManyMethods"})
public class R2dbcExecutionLogStore implements ExecutionLogStore {

  /** Events that may wait for the writer. */
  private static final int QUEUE_CAPACITY = 10_000;

  /** Most events written by one statement. */
  private static final int BATCH_SIZE = 100;

  /** Longest one statement may take. */
  private static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(30);

  /** Id of the changes that belong to no run. */
  private static final String SCHEDULER = ExecutionLogEvent.SCHEDULER;

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the events as JSON. */
  private final ObjectMapper mapper;

  /** Runs kept when the log is trimmed. */
  private final int retainedRuns;

  /** Appended events after which the log is trimmed. */
  private final int compactAfter;

  /** How long {@link #close()} waits for queued events, in milliseconds. */
  private final long shutdownWaitMillis;

  /** Events that never reached the table: the queue was full. Alert on a rise. */
  private final Counter droppedFull;

  /** Events that never reached the table: the insert failed, also one by one. Alert on a rise. */
  private final Counter droppedFailed;

  /** The one thread that touches the database. */
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

  /** Events waiting to be written; the writer takes them in batches. */
  private final BlockingQueue<ExecutionLogEvent> pending;

  /** Events appended since the last trim; only touched on the writer thread. */
  private int sinceCompaction;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the events
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   * @param shutdownWaitMillis how long to wait for queued events on shutdown
   */
  public R2dbcExecutionLogStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final int retainedRuns,
      final int compactAfter,
      final long shutdownWaitMillis) {
    this(client, mapper, retainedRuns, compactAfter, shutdownWaitMillis, new SimpleMeterRegistry());
  }

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the events
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   * @param shutdownWaitMillis how long to wait for queued events on shutdown
   * @param meters where {@code yukta.execution.log.lost} counts events that were not stored
   */
  public R2dbcExecutionLogStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final int retainedRuns,
      final int compactAfter,
      final long shutdownWaitMillis,
      final MeterRegistry meters) {
    this(
        client,
        mapper,
        retainedRuns,
        compactAfter,
        shutdownWaitMillis,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        meters);
  }

  /* default */ R2dbcExecutionLogStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final int retainedRuns,
      final int compactAfter,
      final long shutdownWaitMillis,
      final BlockingQueue<ExecutionLogEvent> pending,
      final MeterRegistry meters) {
    this.droppedFull =
        Counter.builder("yukta.execution.log.lost")
            .description("Execution log events that were not stored")
            .tag("reason", "queue_full")
            .register(meters);
    this.droppedFailed =
        Counter.builder("yukta.execution.log.lost")
            .description("Execution log events that were not stored")
            .tag("reason", "write_failed")
            .register(meters);
    this.pending = pending;
    this.client = client;
    this.mapper = mapper;
    this.retainedRuns = Math.max(1, retainedRuns);
    this.compactAfter = Math.max(1, compactAfter);
    this.shutdownWaitMillis = shutdownWaitMillis;
  }

  @Override
  public void append(final ExecutionLogEvent event) {
    if (!pending.offer(event)) {
      droppedFull.increment();
      log.atError()
          .addKeyValue("executionId", event.executionId())
          .log("Execution log is falling behind, event dropped");
      return;
    }
    try {
      // one task per event, but the first one to run writes everything waiting, in batches
      writer.execute(this::drainAll);
    } catch (final RejectedExecutionException e) {
      log.atError()
          .setCause(e)
          .log("Execution log writer is stopped or busy, the event waits for the next write");
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
          client.sql("DELETE FROM execution_log").fetch().rowsUpdated().block(STATEMENT_TIMEOUT);
          events.forEach(this::insert);
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
      return writer
          .submit(
              () -> {
                drainAll();
                return work.call();
              })
          .get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return fallback;
    } catch (final ExecutionException | RejectedExecutionException e) {
      log.atError().setCause(e).log("Could not access the execution log");
      return fallback;
    }
  }

  /** Writes the waiting events in batches of at most {@link #BATCH_SIZE}; writer thread only. */
  private void drainAll() {
    final List<ExecutionLogEvent> batch = new ArrayList<>(BATCH_SIZE);
    while (pending.drainTo(batch, BATCH_SIZE) > 0) {
      insertBatch(batch);
      sinceCompaction += batch.size();
      batch.clear();
      if (sinceCompaction >= compactAfter) {
        sinceCompaction = 0;
        compact();
      }
    }
  }

  /** One multi-row insert; if it fails the events are tried one by one so none is lost. */
  private void insertBatch(final List<ExecutionLogEvent> batch) {
    try {
      final StringBuilder sql =
          new StringBuilder(128).append("INSERT INTO execution_log (execution_id, event) VALUES ");
      final List<String> json = new ArrayList<>(batch.size());
      for (int i = 0; i < batch.size(); i++) {
        sql.append(i == 0 ? "" : ", ")
            .append("(:e")
            .append(i)
            .append(", CAST(:j")
            .append(i)
            .append(" AS jsonb))");
        json.add(json(batch.get(i)));
      }
      DatabaseClient.GenericExecuteSpec spec = client.sql(sql.toString());
      for (int i = 0; i < batch.size(); i++) {
        spec = spec.bind("e" + i, batch.get(i).executionId()).bind("j" + i, json.get(i));
      }
      spec.fetch().rowsUpdated().block(STATEMENT_TIMEOUT);
    } catch (final RuntimeException e) {
      log.atWarn().setCause(e).log("Batch insert failed, writing the events one by one");
      batch.forEach(this::insert);
    }
  }

  private void insert(final ExecutionLogEvent event) {
    try {
      client
          .sql("INSERT INTO execution_log (execution_id, event) VALUES (:e, CAST(:j AS jsonb))")
          .bind("e", event.executionId())
          .bind("j", json(event))
          .fetch()
          .rowsUpdated()
          .block(STATEMENT_TIMEOUT);
    } catch (final RuntimeException e) {
      droppedFailed.increment();
      log.atError()
          .addKeyValue("executionId", event.executionId())
          .setCause(e)
          .log("Could not append to the execution log");
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

  private List<ExecutionLogEvent> read() {
    final List<ExecutionLogEvent> events = new ArrayList<>();
    final List<String> rows =
        client
            .sql("SELECT CAST(event AS text) AS event FROM execution_log ORDER BY seq")
            .map(row -> row.get("event", String.class))
            .all()
            .collectList()
            .block(STATEMENT_TIMEOUT);
    for (final String row : Objects.requireNonNullElse(rows, List.<String>of())) {
      try {
        events.add(mapper.readValue(row, ExecutionLogEvent.class));
      } catch (final RuntimeException e) {
        log.atWarn().setCause(e).log("Skipping an unreadable execution log row");
      }
    }
    return events;
  }

  /** Keeps the newest runs (by last event) and every run without an end. */
  private void compact() {
    try {
      client
          .sql(
              """
              DELETE FROM execution_log WHERE execution_id <> :scheduler
              AND execution_id NOT IN (
                SELECT execution_id FROM execution_log WHERE execution_id <> :scheduler
                GROUP BY execution_id ORDER BY max(seq) DESC LIMIT :runs)
              AND execution_id NOT IN (
                SELECT execution_id FROM execution_log GROUP BY execution_id
                HAVING bool_or(event ->> 'type' = 'RUN_STARTED')
                   AND NOT bool_or(event ->> 'type' IN ('RUN_FINISHED', 'RUN_ABORTED')))
              """)
          .bind("scheduler", SCHEDULER)
          .bind("runs", retainedRuns)
          .fetch()
          .rowsUpdated()
          .block(STATEMENT_TIMEOUT);
      client
          .sql(
              """
              DELETE FROM execution_log WHERE execution_id = :scheduler AND seq NOT IN (
                SELECT seq FROM execution_log WHERE execution_id = :scheduler
                ORDER BY seq DESC LIMIT :runs)
              """)
          .bind("scheduler", SCHEDULER)
          .bind("runs", retainedRuns)
          .fetch()
          .rowsUpdated()
          .block(STATEMENT_TIMEOUT);
    } catch (final RuntimeException e) {
      log.atError().setCause(e).log("Could not trim the execution log");
    }
  }
}
