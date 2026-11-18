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
 * replaying part of it. {@link #finishedAsync} and {@link #completeAsync} do not block (the
 * deprecated blocking ones wait for them). A database that does not answer reads as "not finished",
 * so a resume runs the node again.
 */
@Slf4j
public class R2dbcNodeCheckpointStore implements NodeCheckpointStore, ByteCounted, RunOutputSweep {

  /** The table of messages. */
  private static final String MESSAGES = "node_checkpoint";

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
   * Creates the store; saved messages are refused once another server holds the leader lease.
   * ponytail: only message rows are fenced; the node state flags (done/incomplete) are not.
   *
   * @param client runs the statements
   * @param mapper reads and writes messages
   * @param meters where refused writes are counted
   * @param fencingToken this server's fencing number, null when no election runs
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // client and mapper are shared Spring beans
  public R2dbcNodeCheckpointStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final io.micrometer.core.instrument.MeterRegistry meters,
      final LongSupplier fencingToken) {
    this.client = client;
    this.mapper = mapper;
    this.fencing = new Fencing(fencingToken, meters, "node_checkpoint");
    this.size = new TableSizes(client, List.of(MESSAGES));
    this.rows = new CheckpointRows(client, fencing);
  }

  @Override
  public Mono<Void> save(final String executionId, final String nodeId, final Message<?> message) {
    return Mono.fromCallable(() -> CheckpointJson.write(mapper, message))
        .flatMap(json ->
            rows.insert(executionId, nodeId, json)
                .doOnNext(written -> {
                  if (written == 0) {
                    fencing.refused(1);
                  }
                }))
        .onErrorResume(error -> {
          log.atWarn()
              .addKeyValue("executionId", executionId)
              .addKeyValue("nodeId", nodeId)
              .setCause(error)
              .log("Could not record node output, the node cannot be restarted from");
          return rows.state(executionId, nodeId, CheckpointRows.INCOMPLETE).then(Mono.empty());
        })
        .then();
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

  @Override
  public Mono<Void> clearAsync(final String executionId) {
    return client
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
                .then())
        .timeout(CheckpointRows.QUESTION_TIMEOUT)
        .onErrorResume(error -> {
          log.atWarn()
              .addKeyValue("executionId", executionId)
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
              .addKeyValue("executionId", executionId)
              .addKeyValue("nodeId", nodeId)
              .setCause(error)
              .log("Could not make the end of a node durable, a resume will run it again");
          return rows.state(executionId, nodeId, CheckpointRows.INCOMPLETE)
              .onErrorResume(_ -> Mono.empty());
        });
  }

  @Override
  public Mono<Boolean> completeAsync(final String executionId, final String nodeId) {
    return rows.flag(executionId, nodeId, CheckpointRows.INCOMPLETE).map(incomplete -> !incomplete);
  }

  @Override
  public Mono<Boolean> finishedAsync(final String executionId, final String nodeId) {
    return Mono.zip(
            rows.flag(executionId, nodeId, CheckpointRows.DONE),
            rows.flag(executionId, nodeId, CheckpointRows.INCOMPLETE))
        .map(t -> t.getT1() && !t.getT2());
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

  /** Deletes the output of every run not in {@code kept}, in one transaction. */
  @Override
  public void removeNotIn(final Set<String> kept) {
    final String[] ids = kept.toArray(String[]::new);
    try {
      Transactions.inTransaction(
              client,
              client
                  .sql("DELETE FROM node_checkpoint WHERE NOT (execution_id = ANY(:k))")
                  .bind("k", ids)
                  .fetch()
                  .rowsUpdated()
                  .then(
                      client
                          .sql(
                              "DELETE FROM node_checkpoint_state WHERE NOT (execution_id ="
                                  + " ANY(:k))")
                          .bind("k", ids)
                          .fetch()
                          .rowsUpdated()
                          .then()))
          .toFuture()
          .get(CheckpointRows.QUESTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final ExecutionException | TimeoutException e) {
      log.atWarn().setCause(e).log("Could not remove the output of runs that are gone");
    }
  }
}
