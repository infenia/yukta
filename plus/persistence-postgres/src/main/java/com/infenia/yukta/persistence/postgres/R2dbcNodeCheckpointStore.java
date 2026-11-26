// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.store.ByteCounted;
import com.infenia.yukta.service.store.CheckpointJson;
import com.infenia.yukta.service.store.RunOutputSweep;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link NodeCheckpointStore} in PostgreSQL, so a run can be resumed by any server that shares the
 * database. Selected with {@code yukta.restart.store-type=postgres}.
 *
 * <p>One row per message (JSONB; PostgreSQL stores large values out of line, so output is not
 * capped) and one state row per node with the end marker. Like the file store, a message that
 * cannot be written marks the node incomplete, so a restart that needs it is refused instead of
 * replaying part of it. The output of one run is capped at {@code yukta.restart.max-bytes-per-run}
 * (JSON text, counted by this server): a node whose output goes over it is marked incomplete the
 * same way, so a resume runs it again and a large streaming run cannot fill the database. {@link
 * #finishedAsync} and {@link #completeAsync} do not block (the deprecated blocking ones wait for
 * them). A database that does not answer reads as "not finished", so a resume runs the node again.
 */
@Slf4j
public class R2dbcNodeCheckpointStore implements NodeCheckpointStore, ByteCounted, RunOutputSweep {

  /** The default of {@code yukta.restart.max-bytes-per-run}: 1 GiB. */
  public static final long DEFAULT_MAX_BYTES_PER_RUN = 1L << 30;

  /** The table of messages. */
  private static final String MESSAGES = "node_checkpoint";

  /** The log key of the run id. */
  private static final String EXECUTION_ID = "executionId";

  /** Longest the sweep of gone runs at recovery may take; the rest waits for the next one. */
  private static final java.time.Duration SWEEP_TIMEOUT = java.time.Duration.ofMinutes(5);

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes messages. */
  private final ObjectMapper mapper;

  /** Refuses saves once another server has taken over the lease. */
  private final Fencing fencing;

  /** The size of the message table, the same on every server sharing the database. */
  private final TableSizes size;

  /** The statements that write messages and the node state flags. */
  private final CheckpointRows rows;

  /** The cap on the JSON text one run may record. */
  private final RunByteCap cap;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes messages
   */
  public R2dbcNodeCheckpointStore(final DatabaseClient client, final ObjectMapper mapper) {
    this(client, mapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null);
  }

  /**
   * Creates the store; saved messages and node state flags are refused once another server holds
   * the leader lease.
   *
   * @param client runs the statements
   * @param mapper reads and writes messages
   * @param meters where refused writes are counted
   * @param fencingToken this server's fencing number, null when no election runs
   */
  public R2dbcNodeCheckpointStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final io.micrometer.core.instrument.MeterRegistry meters,
      final LongSupplier fencingToken) {
    this(client, mapper, meters, fencingToken, DEFAULT_MAX_BYTES_PER_RUN);
  }

  /**
   * Creates the store with a cap on the output of one run.
   *
   * @param client runs the statements
   * @param mapper reads and writes messages
   * @param meters where refused writes are counted
   * @param fencingToken this server's fencing number, null when no election runs
   * @param maxBytesPerRun most JSON text one run may record, at least 1
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // client and mapper are shared Spring beans
  public R2dbcNodeCheckpointStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final io.micrometer.core.instrument.MeterRegistry meters,
      final LongSupplier fencingToken,
      final long maxBytesPerRun) {
    this.cap = new RunByteCap(maxBytesPerRun);
    this.client = client;
    this.mapper = mapper;
    this.fencing = new Fencing(fencingToken, meters, "node_checkpoint");
    this.size = new TableSizes(client, List.of(MESSAGES));
    this.rows = new CheckpointRows(client, fencing);
  }

  @Override
  public Mono<Void> save(final String executionId, final String nodeId, final Message<?> message) {
    return saveAll(executionId, nodeId, List.of(message));
  }

  /**
   * One statement and one round trip for the whole batch ({@code CheckpointStream} hands over up to
   * 64 messages at a time), instead of one per message. The statement is atomic, so a batch is
   * written whole or not at all.
   */
  @Override
  public Mono<Void> saveAll(
      final String executionId, final String nodeId, final List<Message<?>> messages) {
    if (messages.isEmpty()) {
      return Mono.empty();
    }
    return Mono.fromCallable(
            () -> messages.stream().map(message -> CheckpointJson.write(mapper, message)).toList())
        .flatMap(json ->
            cap.fits(executionId, json)
                ? rows.insertAll(executionId, nodeId, json)
                    .doOnNext(written -> {
                      if (written == 0) {
                        fencing.refused(json.size());
                      }
                    })
                : overCap(executionId, nodeId))
        .onErrorResume(error -> {
          log.atWarn()
              .addKeyValue(EXECUTION_ID, executionId)
              .addKeyValue("nodeId", nodeId)
              .setCause(error)
              .log("Could not record node output, the node cannot be restarted from");
          return rows.state(executionId, nodeId, CheckpointRows.INCOMPLETE).then(Mono.empty());
        })
        .then();
  }

  /** Marks the node incomplete, once, when its output went over the run's cap. */
  private Mono<Long> overCap(final String executionId, final String nodeId) {
    if (!cap.firstCapped(executionId, nodeId)) {
      return Mono.empty();
    }
    log.atWarn()
        .addKeyValue(EXECUTION_ID, executionId)
        .addKeyValue("nodeId", nodeId)
        .addKeyValue("maxBytesPerRun", cap.max())
        .log("Run output over yukta.restart.max-bytes-per-run, a resume will run the node again");
    return rows.state(executionId, nodeId, CheckpointRows.INCOMPLETE).then(Mono.empty());
  }

  @Override
  public Mono<Message<?>> get(final String executionId, final String nodeId) {
    return client
        .sql(
            "SELECT CAST(message AS text) AS message FROM node_checkpoint"
                + " WHERE execution_id = :e AND node_id = :n ORDER BY seq DESC LIMIT 1")
        .bind(CheckpointRows.EXECUTION, executionId)
        .bind(CheckpointRows.NODE, nodeId)
        .map(row -> row.get("message", String.class))
        .one()
        .map(json -> CheckpointJson.read(mapper, json));
  }

  @Override
  public Flux<Message<?>> getAll(final String executionId, final String nodeId) {
    return client
        .sql(
            "SELECT CAST(message AS text) AS message FROM node_checkpoint"
                + " WHERE execution_id = :e AND node_id = :n ORDER BY seq")
        .bind(CheckpointRows.EXECUTION, executionId)
        .bind(CheckpointRows.NODE, nodeId)
        .map(row -> row.get("message", String.class))
        .all()
        .map(json -> CheckpointJson.read(mapper, json));
  }

  /**
   * Deletes the run's messages and state rows in one transaction: a half-done clear would leave a
   * node marked done with no output, which a resume would trust.
   */
  @Override
  public Mono<Void> clearAsync(final String executionId) {
    cap.forget(Set.of(executionId), false);
    return Transactions.inTransaction(
            client,
            client
                .sql("DELETE FROM node_checkpoint WHERE execution_id = :e")
                .bind(CheckpointRows.EXECUTION, executionId)
                .fetch()
                .rowsUpdated()
                .then(
                    client
                        .sql("DELETE FROM node_checkpoint_state WHERE execution_id = :e")
                        .bind(CheckpointRows.EXECUTION, executionId)
                        .fetch()
                        .rowsUpdated()
                        .then()))
        .timeout(CheckpointRows.QUESTION_TIMEOUT)
        .onErrorResume(error -> {
          log.atWarn()
              .addKeyValue(EXECUTION_ID, executionId)
              .setCause(error)
              .log("Could not clear the checkpoints of a run");
          return Mono.empty();
        });
  }

  @Override
  @SuppressWarnings("deprecation")
  public void clear(final String executionId) {
    CheckpointRows.await(clearAsync(executionId), null);
  }

  @Override
  public Mono<Void> markComplete(final String executionId, final String nodeId) {
    return rows.state(executionId, nodeId, CheckpointRows.DONE)
        .onErrorResume(error -> {
          log.atError()
              .addKeyValue(EXECUTION_ID, executionId)
              .addKeyValue("nodeId", nodeId)
              .setCause(error)
              .log("Could not make the end of a node durable, a resume will run it again");
          return rows.state(executionId, nodeId, CheckpointRows.INCOMPLETE)
              .onErrorResume(_ -> Mono.empty());
        });
  }

  @Override
  public Mono<Boolean> completeAsync(final String executionId, final String nodeId) {
    return rows.flags(executionId, nodeId).map(flags -> !flags.incomplete());
  }

  @Override
  public Mono<Boolean> finishedAsync(final String executionId, final String nodeId) {
    return rows.flags(executionId, nodeId).map(CheckpointRows.Flags::finished);
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isComplete(final String executionId, final String nodeId) {
    return CheckpointRows.await(completeAsync(executionId, nodeId), false);
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isFinished(final String executionId, final String nodeId) {
    return CheckpointRows.await(finishedAsync(executionId, nodeId), false);
  }

  /**
   * The size on disk of the message table (rows, out-of-line values and indexes), read at most once
   * a minute and never waited for: 0 until the first read answers. Cleared rows count until
   * PostgreSQL vacuums them.
   */
  @Override
  public long bytesHeld() {
    return size.bytes(MESSAGES);
  }

  /**
   * Deletes the output of every run not in {@code kept}, a short statement at a time instead of one
   * transaction that a large backlog would run into the statement timeout with. The state rows go
   * first: a node without one reads as unfinished, so a sweep cut short makes a resume run it again
   * rather than trust a partial output. Then each gone run's messages, through the {@code
   * (execution_id, node_id, seq)} index. What is left after {@link #SWEEP_TIMEOUT} is swept at the
   * next recovery.
   */
  @Override
  public void removeNotIn(final Set<String> kept) {
    cap.forget(kept, true);
    final String[] ids = kept.toArray(String[]::new);
    try {
      client
          .sql("DELETE FROM node_checkpoint_state WHERE NOT (execution_id = ANY(:k))")
          .bind("k", ids)
          .fetch()
          .rowsUpdated()
          .thenMany(
              client
                  .sql(
                      "SELECT DISTINCT execution_id FROM node_checkpoint"
                          + " WHERE NOT (execution_id = ANY(:k))")
                  .bind("k", ids)
                  .map(row -> Objects.requireNonNull(row.get("execution_id", String.class)))
                  .all())
          .concatMap(gone ->
              Batches.repeat(size ->
                  client
                      .sql(
                          "DELETE FROM node_checkpoint WHERE seq IN (SELECT seq FROM"
                              + " node_checkpoint WHERE execution_id = :e LIMIT :size)")
                      .bind(CheckpointRows.EXECUTION, gone)
                      .bind("size", size)
                      .fetch()
                      .rowsUpdated()))
          .then()
          .toFuture()
          .get(SWEEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final ExecutionException | TimeoutException e) {
      log.atWarn().setCause(e).log("Could not remove the output of runs that are gone");
    }
  }
}
