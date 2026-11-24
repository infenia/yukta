// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * The statements of {@link R2dbcNodeCheckpointStore} that write message rows and read or set the
 * flags of a node's state row. Every write carries the fencing check in the same statement.
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

  /**
   * Inserts the message rows of one node in one statement and one round trip, oldest first. The
   * messages travel as one array parameter, so the statement text is the same for every batch size
   * (one prepared statement), and the statement is atomic: a failure writes none of them.
   *
   * @return the rows written: all of them, or 0 when another server holds the lease
   */
  /* default */ Mono<Long> insertAll(
      final String executionId, final String nodeId, final List<String> json) {
    return fencing
        .bind(
            client
                .sql(
                    "INSERT INTO node_checkpoint (execution_id, node_id, message)"
                        + " SELECT :e, :n, CAST(m AS jsonb)"
                        + " FROM unnest(CAST(:m AS text[])) WITH ORDINALITY AS u(m, o)"
                        + " WHERE "
                        + Fencing.NOT_STALE
                        + " ORDER BY o")
                .bind(EXECUTION, executionId)
                .bind(NODE, nodeId)
                .bind("m", json.toArray(String[]::new)))
        .fetch()
        .rowsUpdated();
  }

  /**
   * Sets one flag of the node's state row, creating the row if needed. Fenced like the messages: a
   * server that lost the lease cannot mark a node done.
   */
  /* default */ Mono<Void> state(
      final String executionId, final String nodeId, final String column) {
    return fencing
        .bind(
            client
                .sql(
                    "INSERT INTO node_checkpoint_state (execution_id, node_id, "
                        + column
                        + ") SELECT :e, :n, TRUE WHERE "
                        + Fencing.NOT_STALE
                        + " ON CONFLICT (execution_id, node_id) DO UPDATE SET "
                        + column
                        + " = TRUE")
                .bind(EXECUTION, executionId)
                .bind(NODE, nodeId))
        .fetch()
        .rowsUpdated()
        .doOnNext(written -> {
          if (written == 0) {
            fencing.refused(1);
          }
        })
        .then();
  }

  /**
   * Reads both flags in one query; a database that does not answer reads as unfinished and
   * incomplete, so a resume runs the node again.
   *
   * @return the flags of the node
   */
  /* default */ Mono<Flags> flags(final String executionId, final String nodeId) {
    return client
        .sql(
            "SELECT done, incomplete FROM node_checkpoint_state"
                + " WHERE execution_id = :e AND node_id = :n")
        .bind(EXECUTION, executionId)
        .bind(NODE, nodeId)
        .map(row ->
            new Flags(
                Boolean.TRUE.equals(row.get(DONE, Boolean.class)),
                Boolean.TRUE.equals(row.get(INCOMPLETE, Boolean.class))))
        .one()
        .defaultIfEmpty(new Flags(false, false))
        .timeout(QUESTION_TIMEOUT)
        .onErrorResume(error -> {
          log.atWarn()
              .setCause(error)
              .log("Could not read the checkpoint state, treating as unfinished");
          return Mono.just(new Flags(false, true));
        });
  }

  /**
   * The state row of a node.
   *
   * @param done the node ended normally
   * @param incomplete its output could not be recorded completely
   */
  /* default */ record Flags(boolean done, boolean incomplete) {

    /* default */ boolean finished() {
      return done && !incomplete;
    }
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
