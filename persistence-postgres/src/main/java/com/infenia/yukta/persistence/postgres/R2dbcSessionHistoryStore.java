// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.store.SessionHistoryStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Chat history of {@code SESSION_MEMORY} nodes in PostgreSQL ({@code
 * yukta.session-memory.store-type=postgres}). With {@code yukta.session-memory.ttl} a session that
 * has not been written for that long is deleted whenever any session is written. Calls wait for the
 * database on the calling thread's own future (not Reactor's {@code block}), and a database that
 * does not answer leaves the history in memory only.
 */
@Slf4j
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
public class R2dbcSessionHistoryStore implements SessionHistoryStore {

  /** The stored turns of one session. */
  private static final TypeReference<List<Map<String, String>>> TURNS = new TypeReference<>() {};

  /** Longest a statement may take. */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the turns as JSON. */
  private final ObjectMapper mapper;

  /** How long an unwritten session is kept; null keeps them all. */
  private final Duration ttl;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the turns
   * @param ttl how long a session that is not written is kept, or null for ever
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // both are shared Spring beans
  public R2dbcSessionHistoryStore(
      final DatabaseClient client, final ObjectMapper mapper, final Duration ttl) {
    this.client = client;
    this.mapper = mapper;
    this.ttl = ttl;
  }

  @Override
  public Optional<List<Map<String, String>>> load(final String sessionId) {
    try {
      return Optional.ofNullable(
              client
                  .sql(
                      "SELECT CAST(turns AS text) AS turns FROM session_history WHERE session_id ="
                          + " :s")
                  .bind("s", sessionId)
                  .map(row -> row.get("turns", String.class))
                  .one()
                  .toFuture()
                  .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
          .map(json -> mapper.readValue(json, TURNS));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (final ExecutionException | TimeoutException | RuntimeException e) {
      log.atWarn().setCause(e).log("Could not read the history of a session; starting empty");
      return Optional.empty();
    }
  }

  @Override
  public void save(final String sessionId, final List<Map<String, String>> turns) {
    try {
      client
          .sql(
              """
              INSERT INTO session_history (session_id, turns, updated_at)
              VALUES (:s, CAST(:t AS jsonb), now())
              ON CONFLICT (session_id)
              DO UPDATE SET turns = EXCLUDED.turns, updated_at = now()
              """)
          .bind("s", sessionId)
          .bind("t", mapper.writeValueAsString(turns))
          .fetch()
          .rowsUpdated()
          .then(expire())
          .toFuture()
          .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final ExecutionException | TimeoutException | RuntimeException e) {
      // history stays in memory; losing durability must not fail the chat
      log.atError().setCause(e).log("Could not store the history of a session");
    }
  }

  private Mono<Void> expire() {
    return ttl == null
        ? Mono.empty()
        : client
            .sql(
                "DELETE FROM session_history"
                    + " WHERE updated_at < now() - make_interval(secs => :secs)")
            .bind("secs", ttl.toMillis() / 1000.0)
            .fetch()
            .rowsUpdated()
            .then();
  }
}
