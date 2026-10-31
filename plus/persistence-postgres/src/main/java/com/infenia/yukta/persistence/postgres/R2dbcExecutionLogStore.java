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
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the execution log in PostgreSQL.
 *
 * <p>The store interface is blocking, so all database access runs on one background thread, like
 * the file store: {@link #append} only queues the event; {@link #readAll} and {@link #replaceAll}
 * wait for the queued events first. After {@code compact-after} events the log is trimmed to the
 * newest {@code retained-runs} runs; a run without an end is never dropped.
 *
 * <p>The events that start and end a run are never dropped for a full queue: they wait in a second,
 * unbounded queue (one entry per run, so it stays small) that the writer empties after the batches.
 * If their insert fails it is retried with a growing delay on the writer thread, which is not a
 * reactive thread. Only after the retries fail are they counted as lost.
 */
@Slf4j
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.TooManyMethods"})
public class R2dbcExecutionLogStore implements ExecutionLogStore {

  /** Events that may wait for the writer. */
  private static final int QUEUE_CAPACITY = 10_000;

  /** Most events written by one statement. */
  private static final int BATCH_SIZE = 100;

  /** How often the insert of a run start or end is retried; the delay doubles each time. */
  private static final int CRITICAL_RETRIES = 4;

  /** The event types that start and end a run. */
  private static final Set<ExecutionLogEvent.Type> RUN_EDGES =
      EnumSet.of(
          ExecutionLogEvent.Type.RUN_STARTED,
          ExecutionLogEvent.Type.RUN_FINISHED,
          ExecutionLogEvent.Type.RUN_ABORTED);

  /** Longest one statement may take. */
  private static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(30);

  /** Longest the rewrite of the whole log may take. */
  private static final Duration REWRITE_TIMEOUT = Duration.ofMinutes(2);

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

  /** Refuses writes once another server has taken over the lease. */
  private final Fencing fencing;

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

  /** Run start and end events that did not fit the bounded queue; written after it. */
  private final Queue<ExecutionLogEvent> urgent = new ConcurrentLinkedQueue<>();

  /** Delay before the first retry of a run start or end. */
  private final long retryMillis;

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
        meters,
        200);
  }

  /**
   * Creates the store; its writes are refused once another server holds the leader lease.
   *
   * @param client runs the statements
   * @param mapper reads and writes the events
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   * @param shutdownWaitMillis how long to wait for queued events on shutdown
   * @param meters where {@code yukta.execution.log.lost} counts events that were not stored
   * @param fencingToken this server's fencing number, null when no election runs
   */
  public R2dbcExecutionLogStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final int retainedRuns,
      final int compactAfter,
      final long shutdownWaitMillis,
      final MeterRegistry meters,
      final LongSupplier fencingToken) {
    this(
        client,
        mapper,
        retainedRuns,
        compactAfter,
        shutdownWaitMillis,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        meters,
        200,
        fencingToken);
  }

  /* default */ R2dbcExecutionLogStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final int retainedRuns,
      final int compactAfter,
      final long shutdownWaitMillis,
      final BlockingQueue<ExecutionLogEvent> pending,
      final MeterRegistry meters,
      final long retryMillis) {
    this(
        client,
        mapper,
        retainedRuns,
        compactAfter,
        shutdownWaitMillis,
        pending,
        meters,
        retryMillis,
        null);
  }

  /* default */ R2dbcExecutionLogStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final int retainedRuns,
      final int compactAfter,
      final long shutdownWaitMillis,
      final BlockingQueue<ExecutionLogEvent> pending,
      final MeterRegistry meters,
      final long retryMillis,
      final LongSupplier fencingToken) {
    this.retryMillis = retryMillis;
    this.fencing = new Fencing(fencingToken, meters, "execution_log");
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
      if (!critical(event)) {
        droppedFull.increment();
        log.atError()
            .addKeyValue("executionId", event.executionId())
            .log("Execution log is falling behind, event dropped");
        return;
      }
      urgent.add(event);
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

  /** Uses the {@code execution_id} index, so a lookup does not read the table. */
  @Override
  public List<ExecutionLogEvent> forExecution(final String executionId) {
    return onWriter(
        () ->
            parse(
                client
                    .sql(
                        "SELECT CAST(event AS text) AS event FROM execution_log"
                            + " WHERE execution_id = :id ORDER BY seq")
                    .bind("id", executionId)
                    .map(row -> row.get("event", String.class))
                    .all()
                    .collectList()
                    .block(STATEMENT_TIMEOUT)),
        List.of());
  }

  @Override
  public List<ExecutionLogEvent> ofType(
      final ExecutionLogEvent.Type type, final java.time.Instant since) {
    return onWriter(
        () ->
            parse(
                    client
                        .sql(
                            "SELECT CAST(event AS text) AS event FROM execution_log"
                                + " WHERE event->>'type' = :type ORDER BY seq")
                        .bind("type", type.name())
                        .map(row -> row.get("event", String.class))
                        .all()
                        .collectList()
                        .block(STATEMENT_TIMEOUT))
                .stream()
                .filter(event -> event.timestamp().isAfter(since))
                .toList(),
        List.of());
  }

  @Override
  public List<ExecutionLogEvent> readAll() {
    return onWriter(this::read, List.of());
  }

  /**
   * Swaps the log for {@code events} in one transaction: a crash, a lost connection or a failed
   * insert rolls everything back and leaves the old log, never an empty or partial one.
   */
  @Override
  public void replaceAll(final List<ExecutionLogEvent> events) {
    onWriter(
        () -> {
          final TransactionalOperator transaction =
              TransactionalOperator.create(
                  new R2dbcTransactionManager(client.getConnectionFactory()));
          final List<String> json = events.stream().map(this::json).toList();
          lockLease()
              .then(client.sql("DELETE FROM execution_log").fetch().rowsUpdated())
              .thenMany(
                  Flux.range(0, (events.size() + BATCH_SIZE - 1) / BATCH_SIZE)
                      .concatMap(
                          chunk -> {
                            final int start = chunk * BATCH_SIZE;
                            final int end = Math.min(events.size(), start + BATCH_SIZE);
                            return batchInsert(events.subList(start, end), json.subList(start, end))
                                .fetch()
                                .rowsUpdated();
                          }))
              .then()
              .as(transaction::transactional)
              .block(REWRITE_TIMEOUT);
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

  /** A run start or end: losing it stops the run from being resumed or recovered. */
  private static boolean critical(final ExecutionLogEvent event) {
    return RUN_EDGES.contains(event.type());
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
    for (ExecutionLogEvent event = urgent.poll(); event != null; event = urgent.poll()) {
      insert(event);
    }
  }

  /** One multi-row insert; if it fails the events are tried one by one so none is lost. */
  private void insertBatch(final List<ExecutionLogEvent> batch) {
    try {
      write(batch);
    } catch (final RuntimeException e) {
      log.atWarn().setCause(e).log("Batch insert failed, writing the events one by one");
      batch.forEach(this::insert);
    }
  }

  /** The statement that inserts {@code batch}, whose events are already written as {@code json}. */
  private DatabaseClient.GenericExecuteSpec batchInsert(
      final List<ExecutionLogEvent> batch, final List<String> json) {
    final StringBuilder sql =
        new StringBuilder(128)
            .append("INSERT INTO execution_log (execution_id, event) SELECT a, b FROM (VALUES ");
    for (int i = 0; i < batch.size(); i++) {
      sql.append(i == 0 ? "" : ", ")
          .append("(:e")
          .append(i)
          .append(", CAST(:j")
          .append(i)
          .append(" AS jsonb))");
    }
    sql.append(") AS v(a, b) WHERE ").append(Fencing.NOT_STALE);
    DatabaseClient.GenericExecuteSpec spec = client.sql(sql.toString());
    for (int i = 0; i < batch.size(); i++) {
      spec = spec.bind("e" + i, batch.get(i).executionId()).bind("j" + i, json.get(i));
    }
    return fencing.bind(spec);
  }

  /**
   * Locks the lease row until the transaction ends, so no takeover can happen meanwhile, and fails
   * if another server already holds a newer fencing number.
   */
  private Mono<Long> lockLease() {
    return client
        .sql(
            "SELECT fencing FROM leader_lease WHERE name = 'leader' AND fencing > :fence FOR SHARE")
        .bind(Fencing.PARAM, fencing.bound())
        .fetch()
        .first()
        .flatMap(
            _ -> {
              fencing.refused(1);
              return Mono.<Long>error(new IllegalStateException("The leader lease was taken over"));
            })
        .defaultIfEmpty(0L);
  }

  /** Inserts {@code batch}; the statement writes nothing if another server holds the lease. */
  private void write(final List<ExecutionLogEvent> batch) {
    final Long written =
        batchInsert(batch, batch.stream().map(this::json).toList())
            .fetch()
            .rowsUpdated()
            .block(STATEMENT_TIMEOUT);
    if (Objects.equals(written, 0L)) {
      fencing.refused(batch.size());
    }
  }

  /** Inserts one event; a run start or end is retried with a growing delay before it is lost. */
  private void insert(final ExecutionLogEvent event) {
    final int retries = critical(event) ? CRITICAL_RETRIES : 0;
    for (int attempt = 0; ; attempt++) {
      try {
        write(List.of(event));
        return;
      } catch (final RuntimeException e) {
        if (attempt >= retries || !pause(retryMillis << attempt)) {
          droppedFailed.increment();
          log.atError()
              .addKeyValue("executionId", event.executionId())
              .setCause(e)
              .log("Could not append to the execution log");
          return;
        }
      }
    }
  }

  /** Sleeps on the writer thread; false if interrupted, so shutdown is not held up. */
  private static boolean pause(final long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
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
    return parse(
        client
            .sql("SELECT CAST(event AS text) AS event FROM execution_log ORDER BY seq")
            .map(row -> row.get("event", String.class))
            .all()
            .collectList()
            .block(STATEMENT_TIMEOUT));
  }

  private List<ExecutionLogEvent> parse(final List<String> rows) {
    final List<ExecutionLogEvent> events = new ArrayList<>();
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
