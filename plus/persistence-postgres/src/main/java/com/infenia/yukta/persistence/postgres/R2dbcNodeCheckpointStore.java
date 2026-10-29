// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.store.ByteCounted;
import com.infenia.yukta.service.store.CheckpointJson;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
 * replaying part of it. {@link #isFinished} and {@link #isComplete} are blocking in the interface;
 * they wait for the query on a separate thread and treat a database that does not answer as "not
 * finished", so a resume runs the node again.
 */
@Slf4j
public class R2dbcNodeCheckpointStore implements NodeCheckpointStore, ByteCounted {

  /** Longest a blocking question may wait for the database. */
  private static final Duration QUESTION_TIMEOUT = Duration.ofSeconds(30);

  /** Column: the node's output could not be recorded completely. */
  private static final String INCOMPLETE = "incomplete";

  /** Column: the node ended normally. */
  private static final String DONE = "done";

  /** Execution id parameter. */
  private static final String EXECUTION = "e";

  /** Node id parameter. */
  private static final String NODE = "n";

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes messages. */
  private final ObjectMapper mapper;

  /** Refuses saves once another server has taken over the lease. */
  private final Fencing fencing;

  /** Bytes saved and not yet cleared by this process. */
  private final AtomicLong held = new AtomicLong();

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
  }

  @Override
  public Mono<Void> save(final String executionId, final String nodeId, final Message<?> message) {
    return Mono.fromCallable(() -> CheckpointJson.write(mapper, message))
        .flatMap(
            json ->
                insert(executionId, nodeId, json)
                    .doOnNext(
                        written -> {
                          if (written == 0) {
                            fencing.refused(1);
                          } else {
                            held.addAndGet(json.length());
                          }
                        }))
        .onErrorResume(
            error -> {
              log.atWarn()
                  .addKeyValue("executionId", executionId)
                  .addKeyValue("nodeId", nodeId)
                  .setCause(error)
                  .log("Could not record node output, the node cannot be restarted from");
              return state(executionId, nodeId, INCOMPLETE).then(Mono.empty());
            })
        .then();
  }

  /** Inserts one message row, in the same statement as the fencing check when there is one. */
  private Mono<Long> insert(final String executionId, final String nodeId, final String json) {
    return fencing
        .bind(
            client
                .sql(
                    "INSERT INTO node_checkpoint (execution_id, node_id, message)"
                        + " SELECT :e, :n, CAST(:m AS jsonb) WHERE "
                        + Fencing.NOT_STALE)
                .bind(EXECUTION, executionId)
                .bind(NODE, nodeId)
                .bind("m", json))
        .fetch()
        .rowsUpdated();
  }

  @Override
  public Mono<Message<?>> get(final String executionId, final String nodeId) {
    return getAll(executionId, nodeId).takeLast(1).next();
  }

  @Override
  public Flux<Message<?>> getAll(final String executionId, final String nodeId) {
    return client
        .sql(
            "SELECT CAST(message AS text) AS message FROM node_checkpoint"
                + " WHERE execution_id = :e AND node_id = :n ORDER BY seq")
        .bind(EXECUTION, executionId)
        .bind(NODE, nodeId)
        .map(row -> row.get("message", String.class))
        .all()
        .map(json -> CheckpointJson.read(mapper, json));
  }

  @Override
  public void clear(final String executionId) {
    try {
      client
          .sql(
              "DELETE FROM node_checkpoint WHERE execution_id = :e"
                  + " RETURNING octet_length(CAST(message AS text)) AS size")
          .bind(EXECUTION, executionId)
          .map(row -> row.get("size", Integer.class))
          .all()
          .reduce(0L, Long::sum)
          .doOnNext(size -> held.addAndGet(-Math.min(held.get(), size)))
          .then(
              client
                  .sql("DELETE FROM node_checkpoint_state WHERE execution_id = :e")
                  .bind(EXECUTION, executionId)
                  .fetch()
                  .rowsUpdated()
                  .then())
          .toFuture()
          .get(QUESTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final ExecutionException | TimeoutException e) {
      log.atWarn()
          .addKeyValue("executionId", executionId)
          .setCause(e)
          .log("Could not clear the checkpoints of a run");
    }
  }

  @Override
  public Mono<Void> markComplete(final String executionId, final String nodeId) {
    return state(executionId, nodeId, DONE)
        .onErrorResume(
            error -> {
              log.atError()
                  .addKeyValue("executionId", executionId)
                  .addKeyValue("nodeId", nodeId)
                  .setCause(error)
                  .log("Could not make the end of a node durable, a resume will run it again");
              return state(executionId, nodeId, INCOMPLETE).onErrorResume(_ -> Mono.empty());
            });
  }

  @Override
  public boolean isComplete(final String executionId, final String nodeId) {
    return !flag(executionId, nodeId, INCOMPLETE);
  }

  @Override
  public boolean isFinished(final String executionId, final String nodeId) {
    return flag(executionId, nodeId, DONE) && !flag(executionId, nodeId, INCOMPLETE);
  }

  @Override
  public long bytesHeld() {
    return held.get();
  }

  /** Sets one flag of the node's state row, creating the row if needed. */
  private Mono<Void> state(final String executionId, final String nodeId, final String column) {
    return client
        .sql(
            "INSERT INTO node_checkpoint_state (execution_id, node_id, "
                + column
                + ") VALUES (:e, :n, TRUE) ON CONFLICT (execution_id, node_id) DO UPDATE SET "
                + column
                + " = TRUE")
        .bind(EXECUTION, executionId)
        .bind(NODE, nodeId)
        .fetch()
        .rowsUpdated()
        .then();
  }

  /** Reads one flag, waiting for the database off the caller's reactor thread. */
  private boolean flag(final String executionId, final String nodeId, final String column) {
    try {
      return Boolean.TRUE.equals(
          client
              .sql(
                  "SELECT "
                      + column
                      + " AS flag FROM node_checkpoint_state"
                      + " WHERE execution_id = :e AND node_id = :n")
              .bind(EXECUTION, executionId)
              .bind(NODE, nodeId)
              .map(row -> row.get("flag", Boolean.class))
              .one()
              .toFuture()
              .get(QUESTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return INCOMPLETE.equals(column);
    } catch (final ExecutionException | TimeoutException e) {
      log.atWarn().setCause(e).log("Could not read the checkpoint state, treating as unfinished");
      return INCOMPLETE.equals(column);
    }
  }
}
