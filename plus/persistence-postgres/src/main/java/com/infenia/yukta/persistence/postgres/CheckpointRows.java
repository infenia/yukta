// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * The statements of {@link R2dbcNodeCheckpointStore} that write one message row and read or set the
 * flags of a node's state row.
 */
@Slf4j
final class CheckpointRows {

  /** Longest a blocking question may wait for the database. */
  /* default */ static final Duration QUESTION_TIMEOUT = Duration.ofSeconds(30);

  /** Column: the node's output could not be recorded completely. */
  /* default */ static final String INCOMPLETE = "incomplete";

  /** Column: the node ended normally. */
  /* default */ static final String DONE = "done";

  /** Execution id parameter. */
  /* default */ static final String EXECUTION = "e";

  /** Node id parameter. */
  /* default */ static final String NODE = "n";

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Refuses inserts once another server has taken over the lease. */
  private final Fencing fencing;

  /* default */ CheckpointRows(final DatabaseClient client, final Fencing fencing) {
    this.client = client;
    this.fencing = fencing;
  }

  /** Inserts one message row, in the same statement as the fencing check when there is one. */
  /* default */ Mono<Long> insert(
      final String executionId, final String nodeId, final String json) {
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

  /** Sets one flag of the node's state row, creating the row if needed. */
  /* default */ Mono<Void> state(
      final String executionId, final String nodeId, final String column) {
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

  /** Reads one flag; a database that does not answer reads as unfinished and incomplete. */
  /* default */ Mono<Boolean> flag(
      final String executionId, final String nodeId, final String column) {
    return client
        .sql(
            "SELECT "
                + column
                + " AS flag FROM node_checkpoint_state"
                + " WHERE execution_id = :e AND node_id = :n")
        .bind(EXECUTION, executionId)
        .bind(NODE, nodeId)
        .map(row -> Boolean.TRUE.equals(row.get("flag", Boolean.class)))
        .one()
        .defaultIfEmpty(false)
        .timeout(QUESTION_TIMEOUT)
        .onErrorResume(error -> {
          log.atWarn()
              .setCause(error)
              .log("Could not read the checkpoint state, treating as unfinished");
          return Mono.just(INCOMPLETE.equals(column));
        });
  }

  /** Waits for a deprecated blocking call; an interrupted caller gets {@code fallback}. */
  /* default */ static <T> T await(final Mono<T> mono, final T fallback) {
    try {
      return mono.toFuture().get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return fallback;
    } catch (final ExecutionException e) {
      throw Exceptions.propagate(e);
    }
  }
}
