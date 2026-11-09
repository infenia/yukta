// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.input.Asked;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Vote;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.input.delivery.InputDelivery;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Input requests, their answers and the queue of deliveries in PostgreSQL (tables {@code
 * input_request}, {@code input_response} and {@code input_delivery}, migration V9), so an open
 * request, its deadlines, its votes and its queued deliveries survive a restart.
 *
 * <p>Every change of a request is one {@code UPDATE ... WHERE version = :expected}, so two changes
 * at once produce one winner, on one node or on two. {@link #dueBefore} does not lock the rows it
 * returns: the broker changes a request only through {@link #compareAndSet}, so a due request taken
 * by two sweepers is ended once.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
public class R2dbcInputRequestStore implements InputRequestStore {

  /** The states of a request that can still change. */
  private static final String LIVE = "state IN ('OPEN', 'ESCALATED')";

  /** The columns of a request, in the order {@link #request} reads them by name. */
  private static final String REQUEST_COLUMNS =
      "id, req_key, session_id, workflow_id, execution_id, node_id, state, version, tier,"
          + " CAST(targets AS text) AS targets, tier_deadline, deadline, CAST(spec AS text) AS"
          + " spec, advice, CAST(votes AS text) AS votes, created_at, updated_at,"
          + " CAST(asked AS text) AS asked";

  /** The stored targets of a request. */
  private static final TypeReference<List<String>> TARGETS = new TypeReference<>() {};

  /** The stored steps of a request. */
  private static final TypeReference<List<Asked>> ASKED = new TypeReference<>() {};

  /** The stored votes of a request. */
  private static final TypeReference<List<Vote>> VOTES = new TypeReference<>() {};

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the JSON columns. */
  private final ObjectMapper mapper;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the JSON columns
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // both are shared Spring beans
  public R2dbcInputRequestStore(final DatabaseClient client, final ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  public Mono<InputRequest> createIfAbsent(final InputRequest request) {
    // a second try covers the live request with this key ending between the insert and the read
    return insert(request).switchIfEmpty(Mono.defer(() -> insert(request))).single();
  }

  /** Inserts, or finds the live request with the same key; empty when that one ended meanwhile. */
  private Mono<InputRequest> insert(final InputRequest request) {
    return Mono.fromCallable(() -> json(request))
        .flatMap(
            json ->
                client
                    .sql(
                        """
                        INSERT INTO input_request (id, req_key, session_id, workflow_id,
                          execution_id, node_id, kind, risk, state, version, tier, targets,
                          tier_deadline, deadline, spec, advice, votes, created_at, updated_at,
                          asked)
                        VALUES (:id, :key, :session, :workflow, :execution, :node, :kind, :risk,
                          :state, :version, :tier, CAST(:targets AS jsonb), :tierDeadline,
                          :deadline, CAST(:spec AS jsonb), :advice, CAST(:votes AS jsonb),
                          :created, :updated, CAST(:asked AS jsonb))
                        ON CONFLICT DO NOTHING
                        """)
                    .bind("id", request.id())
                    .bind("key", request.key())
                    .bind("session", request.sessionId())
                    .bind("workflow", request.workflowId())
                    .bind("execution", request.executionId())
                    .bind("node", request.nodeId())
                    .bind("kind", request.spec().kind().name())
                    .bind("risk", request.spec().risk().name())
                    .bind("state", request.state().name())
                    .bind("version", request.version())
                    .bind("tier", request.tier())
                    .bind("targets", json.targets())
                    .bind("spec", json.spec())
                    .bind("votes", json.votes())
                    .bind("asked", json.asked())
                    .bind("created", request.createdAt())
                    .bind("updated", request.updatedAt())
                    .bind("advice", nullable(request.advice(), String.class))
                    .bind("tierDeadline", nullable(request.tierDeadline(), Instant.class))
                    .bind("deadline", nullable(request.deadline(), Instant.class))
                    .fetch()
                    .rowsUpdated())
        .flatMap(
            rows ->
                rows > 0
                    ? Mono.just(request)
                    : client
                        .sql(
                            "SELECT "
                                + REQUEST_COLUMNS
                                + " FROM input_request WHERE req_key = :key AND "
                                + LIVE)
                        .bind("key", request.key())
                        .map(this::request)
                        .one());
  }

  @Override
  public Mono<InputRequest> findById(final String id) {
    return client
        .sql("SELECT " + REQUEST_COLUMNS + " FROM input_request WHERE id = :id")
        .bind("id", id)
        .map(this::request)
        .one();
  }

  @Override
  public Mono<InputRequest> findAnsweredByKey(final String key) {
    return client
        .sql(
            "SELECT "
                + REQUEST_COLUMNS
                + " FROM input_request WHERE req_key = :key AND state = 'ANSWERED'"
                + " ORDER BY updated_at DESC, created_at DESC LIMIT 1")
        .bind("key", key)
        .map(this::request)
        .one();
  }

  @Override
  public Mono<Boolean> compareAndSet(
      final String id, final long expectedVersion, final InputRequest next) {
    if (!id.equals(next.id())) {
      return Mono.just(false);
    }
    return Mono.fromCallable(() -> json(next))
        .flatMap(
            json ->
                client
                    .sql(
                        """
                        UPDATE input_request SET state = :state, version = :version,
                          tier = :tier, targets = CAST(:targets AS jsonb),
                          tier_deadline = :tierDeadline, deadline = :deadline,
                          spec = CAST(:spec AS jsonb), advice = :advice,
                          votes = CAST(:votes AS jsonb), asked = CAST(:asked AS jsonb),
                          updated_at = :updated
                        WHERE id = :id AND version = :expected AND state IN ('OPEN', 'ESCALATED')
                        """)
                    .bind("id", id)
                    .bind("expected", expectedVersion)
                    .bind("state", next.state().name())
                    .bind("version", next.version())
                    .bind("tier", next.tier())
                    .bind("targets", json.targets())
                    .bind("spec", json.spec())
                    .bind("votes", json.votes())
                    .bind("asked", json.asked())
                    .bind("updated", next.updatedAt())
                    .bind("advice", nullable(next.advice(), String.class))
                    .bind("tierDeadline", nullable(next.tierDeadline(), Instant.class))
                    .bind("deadline", nullable(next.deadline(), Instant.class))
                    .fetch()
                    .rowsUpdated())
        .map(rows -> rows > 0);
  }

  @Override
  public Mono<InputResponse> saveResponse(final InputResponse response) {
    return Mono.fromCallable(() -> mapper.writeValueAsString(response))
        .flatMap(
            json ->
                client
                    .sql(
                        """
                        INSERT INTO input_response (request_id, response, answered_at)
                        VALUES (:id, CAST(:response AS jsonb), :at)
                        ON CONFLICT (request_id) DO NOTHING
                        """)
                    .bind("id", response.requestId())
                    .bind("response", json)
                    .bind("at", response.answeredAt())
                    .fetch()
                    .rowsUpdated())
        .then(findResponse(response.requestId()));
  }

  @Override
  public Mono<InputResponse> findResponse(final String requestId) {
    return client
        .sql("SELECT CAST(response AS text) AS response FROM input_response WHERE request_id = :id")
        .bind("id", requestId)
        .map(row -> mapper.readValue(row.get("response", String.class), InputResponse.class))
        .one();
  }

  @Override
  public Flux<InputRequest> listOpen(
      final @Nullable String sessionId, final @Nullable String executionId) {
    return query(LIVE, sessionId, executionId);
  }

  @Override
  public Flux<InputRequest> list(
      final @Nullable String sessionId, final @Nullable String executionId) {
    return query("TRUE", sessionId, executionId);
  }

  private Flux<InputRequest> query(
      final String condition,
      final @Nullable String sessionId,
      final @Nullable String executionId) {
    return client
        .sql(
            "SELECT "
                + REQUEST_COLUMNS
                + " FROM input_request WHERE "
                + condition
                + " AND (CAST(:session AS text) IS NULL OR session_id = CAST(:session AS text)) AND"
                + " (CAST(:execution AS text) IS NULL OR execution_id = CAST(:execution AS text))"
                + " ORDER BY created_at, id")
        .bind("session", nullable(sessionId, String.class))
        .bind("execution", nullable(executionId, String.class))
        .map(this::request)
        .all();
  }

  @Override
  public Flux<InputRequest> dueBefore(final Instant now) {
    return client
        .sql(
            "SELECT "
                + REQUEST_COLUMNS
                + " FROM input_request WHERE "
                + LIVE
                + " AND ((deadline IS NOT NULL AND deadline <= :now)"
                + " OR (tier_deadline IS NOT NULL AND tier_deadline <= :now))"
                + " ORDER BY created_at, id")
        .bind("now", now)
        .map(this::request)
        .all();
  }

  @Override
  public Mono<InputDelivery> enqueueDelivery(final InputDelivery delivery) {
    return saveDelivery(delivery).thenReturn(delivery);
  }

  @Override
  public Flux<InputDelivery> dueDeliveries(final Instant now) {
    return client
        .sql(
            "SELECT id, request_id, tier, responder, state, attempts, next_attempt, last_error"
                + " FROM input_delivery WHERE state = 'PENDING' AND next_attempt <= :now"
                + " ORDER BY next_attempt, id")
        .bind("now", now)
        .map(
            row ->
                new InputDelivery(
                    row.get("id", String.class),
                    row.get("request_id", String.class),
                    row.get("tier", String.class),
                    row.get("responder", String.class),
                    InputDelivery.State.valueOf(row.get("state", String.class)),
                    Objects.requireNonNull(row.get("attempts", Integer.class)),
                    row.get("next_attempt", Instant.class),
                    row.get("last_error", String.class)))
        .all();
  }

  @Override
  public Mono<Void> saveDelivery(final InputDelivery delivery) {
    return client
        .sql(
            """
            INSERT INTO input_delivery (id, request_id, tier, responder, state, attempts,
              next_attempt, last_error)
            VALUES (:id, :request, :tier, :responder, :state, :attempts, :next, :error)
            ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state, attempts = EXCLUDED.attempts,
              next_attempt = EXCLUDED.next_attempt, last_error = EXCLUDED.last_error
            """)
        .bind("id", delivery.id())
        .bind("request", delivery.requestId())
        .bind("tier", delivery.tier())
        .bind("responder", delivery.responder())
        .bind("state", delivery.state().name())
        .bind("attempts", delivery.attempts())
        .bind("next", delivery.nextAttempt())
        .bind("error", nullable(delivery.lastError(), String.class))
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public Mono<Long> purgeTerminalOlderThan(final Instant cutoff) {
    return client
        .sql(
            """
            WITH gone AS (
              DELETE FROM input_request WHERE NOT (state IN ('OPEN', 'ESCALATED'))
                AND updated_at < :cutoff RETURNING id),
            answers AS (
              DELETE FROM input_response WHERE request_id IN (SELECT id FROM gone))
            SELECT count(*) AS removed FROM gone
            """)
        .bind("cutoff", cutoff)
        .map(row -> row.get("removed", Long.class))
        .one();
  }

  private InputRequest request(final Readable row) {
    return new InputRequest(
        row.get("id", String.class),
        row.get("req_key", String.class),
        row.get("session_id", String.class),
        row.get("workflow_id", String.class),
        row.get("execution_id", String.class),
        row.get("node_id", String.class),
        mapper.readValue(row.get("spec", String.class), InputRequestSpec.class),
        InputState.valueOf(row.get("state", String.class)),
        Objects.requireNonNull(row.get("version", Long.class)),
        row.get("tier", String.class),
        mapper.readValue(row.get("targets", String.class), TARGETS),
        row.get("advice", String.class),
        row.get("deadline", Instant.class),
        row.get("tier_deadline", Instant.class),
        mapper.readValue(row.get("votes", String.class), VOTES),
        row.get("created_at", Instant.class),
        row.get("updated_at", Instant.class),
        mapper.readValue(row.get("asked", String.class), ASKED));
  }

  private Json json(final InputRequest request) {
    return new Json(
        mapper.writeValueAsString(request.targets()),
        mapper.writeValueAsString(request.spec()),
        mapper.writeValueAsString(request.votes()),
        mapper.writeValueAsString(request.asked()));
  }

  /** The JSON columns of a request. */
  private record Json(String targets, String spec, String votes, String asked) {}

  /** A bindable value: a null needs its type for the driver. */
  private static Object nullable(final @Nullable Object value, final Class<?> type) {
    return value == null ? io.r2dbc.spi.Parameters.in(type) : value;
  }
}
