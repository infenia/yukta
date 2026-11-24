// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the execution log in PostgreSQL.
 *
 * <p>All database work runs through one serial pipeline, so no thread is parked: {@link #append}
 * only queues the event and schedules a drain; {@link #readAll} and {@link #replaceAll} are queued
 * behind it, so they see every event appended before. After {@code compact-after} events the log is
 * trimmed to the newest {@code retained-runs} runs; a run without an end is never dropped.
 *
 * <p>The events that start and end a run are never dropped for a full queue: they wait in a second,
 * unbounded queue (one entry per run, so it stays small) that the drain empties after the batches.
 * If their insert fails it is retried with a growing delay (the pipeline waits, nothing else
 * writes); only after the retries fail are they counted as lost.
 *
 * <p>It is a {@link SmartLifecycle} that stops late (after the runs have ended), flushes what is
 * queued and then closes; Spring waits for that.
 */
@Slf4j
@SuppressWarnings({
  "PMD.AvoidCatchingGenericException",
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports"
})
public class R2dbcExecutionLogStore implements ExecutionLogStore, SmartLifecycle {

  /** Events that may wait for the writer. */
  private static final int QUEUE_CAPACITY = 10_000;

  /** Most events written by one statement. */
  private static final int BATCH_SIZE = 100;

  /** How often the insert of a run start or end is retried; the delay doubles each time. */
  private static final int CRITICAL_RETRIES = 4;

  /** The wait before a kept run start or end is tried again: the retry delay times 2^7. */
  private static final int REQUEUE_SHIFT = 7;

  /** The event types that start and end a run. */
  private static final List<ExecutionLogEvent.Type> RUN_EDGES =
      List.of(
          ExecutionLogEvent.Type.RUN_STARTED,
          ExecutionLogEvent.Type.RUN_FINISHED,
          ExecutionLogEvent.Type.RUN_ABORTED);

  /** Log key of the run an event belongs to. */
  private static final String EXECUTION_ID = "executionId";

  private static final String EVENT = "event";

  private static final String NEWEST_SQL =
      "SELECT CAST(event AS text) AS event FROM execution_log WHERE type IN (";

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

  /** How long {@link #stop(Runnable)} waits for queued events, in milliseconds. */
  private final long shutdownWaitMillis;

  /** Events that never reached the table: the queue was full. Alert on a rise. */
  private final Counter droppedFull;

  /** Events that never reached the table: the insert failed, also one by one. Alert on a rise. */
  private final Counter droppedFailed;

  /** Refuses writes once another server has taken over the lease. */
  private final Fencing fencing;

  /** The work to do, one task at a time and in order: drains, reads and rewrites. */
  private final Sinks.Many<Mono<Void>> tasks =
      Sinks.many().unicast().onBackpressureBuffer(new LinkedBlockingQueue<>());

  /** Lets one thread at a time hand a task over. */
  private final ReentrantLock emitLock = new ReentrantLock();

  /** True while a drain is queued and has not started, so one is enough for many appends. */
  private final AtomicBoolean drainQueued = new AtomicBoolean();

  /** Set when the store stops: nothing new is queued, reads answer empty, no more retries. */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** Events waiting to be written; the writer takes them in batches. */
  private final BlockingQueue<ExecutionLogEvent> pending;

  /** Run start and end events that did not fit the bounded queue; written after it. */
  private final Queue<ExecutionLogEvent> urgent = new ConcurrentLinkedQueue<>();

  /** Delay before the first retry of a run start or end. */
  private final long retryMillis;

  /** Events appended since the last trim; only touched by the pipeline. */
  private final AtomicInteger sinceCompaction = new AtomicInteger();

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
    Gauge.builder("yukta.execution.log.queue", this, R2dbcExecutionLogStore::queued)
        .description("Execution log events waiting for the writer; a steady rise means it is stuck")
        .register(meters);
    this.client = client;
    this.mapper = mapper;
    this.retainedRuns = Math.max(1, retainedRuns);
    this.compactAfter = Math.max(1, compactAfter);
    this.shutdownWaitMillis = shutdownWaitMillis;
    // one task at a time; the work of a task never fails the pipeline
    tasks
        .asFlux()
        .concatMap(task ->
            task.onErrorResume(error -> {
              log.atError().setCause(error).log("Could not access the execution log");
              return Mono.empty();
            }))
        .subscribe();
  }

  @Override
  public void append(final ExecutionLogEvent event) {
    if (closed.get()) {
      log.atError()
          .addKeyValue(EXECUTION_ID, event.executionId())
          .log("Execution log is stopped, event dropped");
      return;
    }
    if (!pending.offer(event)) {
      if (!critical(event)) {
        droppedFull.increment();
        log.atError()
            .addKeyValue(EXECUTION_ID, event.executionId())
            .log("Execution log is falling behind, event dropped");
        return;
      }
      urgent.add(event);
    }
    scheduleDrain();
  }

  /** Queues a drain unless one is already waiting; it writes everything that is queued by then. */
  private void scheduleDrain() {
    if (drainQueued.compareAndSet(false, true)
        && !enqueue(
            Mono.defer(() -> {
              drainQueued.set(false);
              return drainAll();
            }))) {
      drainQueued.set(false);
      log.atDebug().log("Execution log is stopped, the event waits for the next write");
    }
  }

  /** Adds a task to the pipeline; false if the store is stopped. */
  /* default */ boolean enqueue(final Mono<Void> task) {
    final boolean open = !closed.get();
    if (open) {
      emit(task);
    }
    return open;
  }

  /** Adds a task even while the store stops; false if the pipeline has ended. */
  /* default */ boolean emit(final Mono<Void> task) {
    // one emitter at a time (a sink refuses overlapping ones); the work of the task is not done
    // here, so the lock is held only for the hand-over
    emitLock.lock();
    try {
      return tasks.tryEmitNext(task).isSuccess();
    } finally {
      emitLock.unlock();
    }
  }

  /**
   * Runs {@code work} in the pipeline, after everything queued so far, and returns its result; an
   * empty result if the store is stopped, the work fails or the caller has already gone.
   */
  private <T> Mono<T> serial(final Mono<T> work) {
    return Mono.defer(() -> {
      final Sinks.One<T> result = Sinks.one();
      final AtomicBoolean cancelled = new AtomicBoolean();
      final Mono<Void> task =
          Mono.defer(() -> cancelled.get() ? Mono.<T>empty() : work.doOnNext(result::tryEmitValue))
              .doOnError(error -> log.atError().setCause(error).log("Could not access the log"))
              .onErrorResume(_ -> Mono.empty())
              .doFinally(_ -> result.tryEmitEmpty())
              .then();
      // a caller that has gone before its turn comes costs the pipeline nothing
      return enqueue(task) ? result.asMono().doOnCancel(() -> cancelled.set(true)) : Mono.empty();
    });
  }

  /** Uses the {@code (execution_id, seq)} index, so a lookup does not read the table. */
  @Override
  public Flux<ExecutionLogEvent> forExecution(final String executionId) {
    return reading(
        Mono.defer(() ->
            parse(
                client
                    .sql(
                        "SELECT CAST(event AS text) AS event FROM execution_log"
                            + " WHERE execution_id = :id ORDER BY seq")
                    .bind("id", executionId)
                    .map(row -> row.get(EVENT, String.class))
                    .all()
                    .collectList()
                    .timeout(STATEMENT_TIMEOUT))));
  }

  /** One bounded query, newest first, so the audit does not read the table. */
  @Override
  public Flux<ExecutionLogEvent> newest(
      final java.util.Set<ExecutionLogEvent.Type> types,
      final String executionId,
      final int limit) {
    final List<ExecutionLogEvent.Type> wanted = List.copyOf(types);
    if (wanted.isEmpty() || limit < 1) {
      return Flux.empty();
    }
    return reading(
        Mono.defer(() -> {
          final String sql =
              NEWEST_SQL
                  + java.util.stream.IntStream.range(0, wanted.size())
                      .mapToObj(i -> ":t" + i)
                      .collect(java.util.stream.Collectors.joining(", "))
                  + ")"
                  + (executionId == null ? "" : " AND execution_id = :id")
                  + " ORDER BY seq DESC LIMIT :limit";
          DatabaseClient.GenericExecuteSpec spec = client.sql(sql);
          for (int i = 0; i < wanted.size(); i++) {
            spec = spec.bind("t" + i, wanted.get(i).name());
          }
          if (executionId != null) {
            spec = spec.bind("id", executionId);
          }
          return parse(
              spec.bind("limit", limit)
                  .map(row -> row.get(EVENT, String.class))
                  .all()
                  .collectList()
                  .timeout(STATEMENT_TIMEOUT));
        }));
  }

  /** Uses the {@code (type, seq)} index; only the events after {@code since} are read. */
  @Override
  public Flux<ExecutionLogEvent> ofType(
      final ExecutionLogEvent.Type type, final java.time.Instant since) {
    return reading(
        Mono.defer(() ->
            parse(
                client
                    .sql(
                        "SELECT CAST(event AS text) AS event FROM execution_log"
                            + " WHERE type = :type AND ts > :since ORDER BY seq")
                    .bind("type", type.name())
                    .bind("since", since)
                    .map(row -> row.get(EVENT, String.class))
                    .all()
                    .collectList()
                    .timeout(STATEMENT_TIMEOUT))));
  }

  @Override
  public Flux<ExecutionLogEvent> readAll() {
    return reading(
        Mono.defer(() ->
            parse(
                client
                    .sql("SELECT CAST(event AS text) AS event FROM execution_log ORDER BY seq")
                    .map(row -> row.get(EVENT, String.class))
                    .all()
                    .collectList()
                    .timeout(STATEMENT_TIMEOUT))));
  }

  /** Runs a read behind the queued events, so it sees every earlier append. */
  private Flux<ExecutionLogEvent> reading(final Mono<List<ExecutionLogEvent>> read) {
    return serial(drainAll().then(read)).flatMapMany(Flux::fromIterable);
  }

  /**
   * Swaps the log for {@code events} in one transaction: a crash, a lost connection or a failed
   * insert rolls everything back and leaves the old log, never an empty or partial one.
   */
  @Override
  public Mono<Void> replaceAll(final List<ExecutionLogEvent> events) {
    return serial(
            drainAll()
                .then(
                    Mono.defer(() -> {
                      final TransactionalOperator transaction =
                          TransactionalOperator.create(
                              new R2dbcTransactionManager(client.getConnectionFactory()));
                      final List<String> json = events.stream().map(this::json).toList();
                      return lockLease()
                          .then(client.sql("DELETE FROM execution_log").fetch().rowsUpdated())
                          .thenMany(
                              Flux.range(0, (events.size() + BATCH_SIZE - 1) / BATCH_SIZE)
                                  .concatMap(chunk -> {
                                    final int start = chunk * BATCH_SIZE;
                                    final int end = Math.min(events.size(), start + BATCH_SIZE);
                                    return batchInsert(
                                            events.subList(start, end), json.subList(start, end))
                                        .fetch()
                                        .rowsUpdated();
                                  }))
                          .then()
                          .as(transaction::transactional)
                          .timeout(REWRITE_TIMEOUT);
                    }))
                .then(Mono.just(Boolean.TRUE)))
        .then();
  }

  @Override
  public boolean isRunning() {
    return !closed.get();
  }

  @Override
  public void start() {
    // the pipeline starts with the store
  }

  /** Stops after the runs have ended, so their last events are still written. */
  @Override
  public int getPhase() {
    return Integer.MIN_VALUE + 100;
  }

  @Override
  public void stop() {
    stop(() -> {});
  }

  /**
   * Writes what is queued, waits for it for at most the shutdown wait, then closes: nothing more is
   * queued, reads answer empty, and a run start or end that still cannot be written is counted as
   * lost. A task that is still running when the wait ends carries on, and the callback runs.
   *
   * @param callback runs when the store is closed
   */
  @Override
  public void stop(final Runnable callback) {
    if (!closed.compareAndSet(false, true)) {
      callback.run();
      return;
    }
    final Sinks.One<Boolean> flushed = Sinks.one();
    emit(drainAll().doFinally(_ -> flushed.tryEmitValue(Boolean.TRUE)));
    tasks.tryEmitComplete();
    flushed
        .asMono()
        .timeout(Duration.ofMillis(shutdownWaitMillis))
        .onErrorResume(_ -> {
          log.atWarn().log("Execution log writer did not finish in time");
          return Mono.empty();
        })
        .doFinally(_ -> {
          for (ExecutionLogEvent event = urgent.poll(); event != null; event = urgent.poll()) {
            droppedFailed.increment();
            log.atError()
                .addKeyValue(EXECUTION_ID, event.executionId())
                .log("Run start or end not stored before shutdown");
          }
          callback.run();
        })
        .subscribe();
  }

  /** Events waiting for the writer, in both queues. */
  private int queued() {
    return pending.size() + urgent.size();
  }

  /** A run start or end: losing it stops the run from being resumed or recovered. */
  private static boolean critical(final ExecutionLogEvent event) {
    return RUN_EDGES.contains(event.type());
  }

  /** Writes the waiting events in batches of at most {@link #BATCH_SIZE}, then the urgent ones. */
  private Mono<Void> drainAll() {
    return drainPending()
        .then(
            Mono.defer(() ->
                // only the events waiting now: a failed one goes back to the queue for a later
                // drain
                Flux.range(0, urgent.size())
                    .concatMap(_ -> insert(urgent.remove())) // only the pipeline removes
                    .then()));
  }

  private Mono<Void> drainPending() {
    return Mono.defer(() -> {
      final List<ExecutionLogEvent> batch = new ArrayList<>(BATCH_SIZE);
      if (pending.drainTo(batch, BATCH_SIZE) == 0) {
        return Mono.empty();
      }
      return insertBatch(batch)
          .then(
              Mono.defer(() ->
                  sinceCompaction.addAndGet(batch.size()) >= compactAfter
                      ? Mono.defer(() -> {
                        sinceCompaction.set(0);
                        return compact();
                      })
                      : Mono.<Void>empty()))
          .then(Mono.defer(this::drainPending));
    });
  }

  /** One multi-row insert; if it fails the events are tried one by one so none is lost. */
  private Mono<Void> insertBatch(final List<ExecutionLogEvent> batch) {
    return write(batch)
        .onErrorResume(error -> {
          log.atWarn().setCause(error).log("Batch insert failed, writing the events one by one");
          return Flux.fromIterable(batch).concatMap(this::insert).then();
        });
  }

  /**
   * The statement that inserts {@code batch}, whose events are already written as {@code json}. The
   * columns travel as four parallel arrays, so the statement text is the same for every batch size
   * (one prepared statement), and the rows keep the order of the batch.
   */
  private DatabaseClient.GenericExecuteSpec batchInsert(
      final List<ExecutionLogEvent> batch, final List<String> json) {
    final int size = batch.size();
    final String[] runs = new String[size];
    final String[] types = new String[size];
    final String[] times = new String[size];
    for (int i = 0; i < size; i++) {
      final ExecutionLogEvent event = batch.get(i);
      runs[i] = event.executionId();
      types[i] = event.type().name();
      times[i] = event.timestamp().toString();
    }
    return fencing.bind(
        client
            .sql(
                "INSERT INTO execution_log (execution_id, type, ts, event)"
                    + " SELECT e, t, CAST(s AS timestamptz), CAST(j AS jsonb)"
                    + " FROM unnest(CAST(:e AS text[]), CAST(:t AS text[]), CAST(:s AS text[]),"
                    + " CAST(:j AS text[])) WITH ORDINALITY AS u(e, t, s, j, o)"
                    + " WHERE "
                    + Fencing.NOT_STALE
                    + " ORDER BY o")
            .bind("e", runs)
            .bind("t", types)
            .bind("s", times)
            .bind("j", json.toArray(String[]::new)));
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
        .flatMap(_ -> {
          fencing.refused(1);
          return Mono.<Long>error(new IllegalStateException("The leader lease was taken over"));
        })
        .defaultIfEmpty(0L);
  }

  /** Inserts {@code batch}; the statement writes nothing if another server holds the lease. */
  private Mono<Void> write(final List<ExecutionLogEvent> batch) {
    // the JSON of a batch is made off the database's thread
    return Mono.fromCallable(() -> batch.stream().map(this::json).toList())
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(json -> batchInsert(batch, json).fetch().rowsUpdated().timeout(STATEMENT_TIMEOUT))
        .doOnNext(written -> {
          if (Objects.equals(written, 0L)) {
            fencing.refused(batch.size());
          }
        })
        .then();
  }

  /** Inserts one event; a run start or end is retried with a growing delay before it is lost. */
  /* default */ Mono<Void> insert(final ExecutionLogEvent event) {
    final int retries = critical(event) ? CRITICAL_RETRIES : 0;
    return Mono.defer(() -> write(List.of(event)))
        .retryWhen(
            Retry.from(signals ->
                signals.concatMap(signal ->
                    signal.totalRetries() < retries && !closed.get()
                        ? Mono.delay(Duration.ofMillis(retryMillis << signal.totalRetries()))
                        : Mono.<Long>error(signal.failure()))))
        .onErrorResume(error -> {
          if (requeue(event)) {
            return Mono.empty();
          }
          droppedFailed.increment();
          log.atError()
              .addKeyValue(EXECUTION_ID, event.executionId())
              .setCause(error)
              .log("Could not append to the execution log");
          return Mono.empty();
        });
  }

  /**
   * Keeps a run start or end that the retries could not store and tries again later, until the
   * database is back or the store shuts down. False if it cannot wait any longer.
   */
  private boolean requeue(final ExecutionLogEvent event) {
    if (!critical(event) || closed.get()) {
      return false;
    }
    urgent.add(event);
    log.atWarn()
        .addKeyValue(EXECUTION_ID, event.executionId())
        .log("Could not append a run start or end, trying again later");
    Mono.delay(Duration.ofMillis(retryMillis << REQUEUE_SHIFT)).subscribe(_ -> scheduleDrain());
    return true;
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
          .addKeyValue(EXECUTION_ID, event.executionId())
          .log("Run payload cannot be serialised, so the run cannot be resumed");
      return mapper.writeValueAsString(event.withoutPayload());
    }
  }

  private Mono<List<ExecutionLogEvent>> parse(final Mono<List<String>> rows) {
    return rows.map(list -> {
      final List<ExecutionLogEvent> events = new ArrayList<>();
      for (final String row : list) {
        try {
          events.add(mapper.readValue(row, ExecutionLogEvent.class));
        } catch (final RuntimeException e) {
          log.atWarn().setCause(e).log("Skipping an unreadable execution log row");
        }
      }
      return events;
    });
  }

  /**
   * Keeps the newest {@code retained-runs} ended runs (by their last end) and every run without an
   * end. Only the run starts and ends are read (index {@code execution_log_run_edge}, about two
   * rows per run), not the whole log. ponytail: events of a run that has no start or end in the log
   * are not trimmed here; recovery drops them at the next start.
   */
  private Mono<Void> compact() {
    return Mono.defer(() ->
            client
                .sql(
                    """
                    DELETE FROM execution_log WHERE execution_id IN (
                      SELECT execution_id FROM execution_log
                      WHERE type IN ('RUN_FINISHED', 'RUN_ABORTED')
                        AND execution_id <> :scheduler
                      GROUP BY execution_id ORDER BY max(seq) DESC OFFSET :runs)
                    """)
                .bind("scheduler", SCHEDULER)
                .bind("runs", retainedRuns)
                .fetch()
                .rowsUpdated()
                .timeout(STATEMENT_TIMEOUT)
                .then(
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
                        .timeout(STATEMENT_TIMEOUT))
                .then())
        .onErrorResume(error -> {
          log.atError().setCause(error).log("Could not trim the execution log");
          return Mono.empty();
        });
  }
}
