// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.service.activity.ActivityEntry;
import com.infenia.yukta.service.activity.ActivityStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps the activity history in PostgreSQL. Entries older than {@code maxAge}, and all but the
 * newest {@code maxRows}, are deleted after every {@code TRIM_EVERY} appended entries.
 */
public class R2dbcActivityStore implements ActivityStore {

  /** Appended entries between two trims. */
  private static final int TRIM_EVERY = 1_000;

  private static final String INSERT =
      "INSERT INTO activity (at, type, severity, execution_id, envelope)"
          + " VALUES (:at, :type, :severity, :execution, CAST(:envelope AS jsonb))";

  private final DatabaseClient client;
  private final int maxRows;
  private final Duration maxAge;
  private final AtomicInteger sinceTrim = new AtomicInteger();

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param maxRows rows kept
   * @param maxAge how long a row is kept
   */
  public R2dbcActivityStore(final DatabaseClient client, final int maxRows, final Duration maxAge) {
    this.client = client;
    this.maxRows = maxRows;
    this.maxAge = maxAge;
  }

  @Override
  public Mono<Void> append(final List<ActivityEntry> entries) {
    if (entries.isEmpty()) {
      return Mono.empty();
    }
    return Flux.fromIterable(entries)
        .concatMap(e ->
            client
                .sql(INSERT)
                .bind("at", e.at())
                .bind("type", e.type())
                .bind("severity", e.severity())
                .bind("execution", e.executionId())
                .bind("envelope", e.json())
                .then())
        .then(
            Mono.defer(
                () -> sinceTrim.addAndGet(entries.size()) >= TRIM_EVERY ? trim() : Mono.empty()));
  }

  private Mono<Void> trim() {
    sinceTrim.set(0);
    return client
        .sql(
            "DELETE FROM activity WHERE at < :cutoff"
                + " OR seq <= (SELECT COALESCE(MAX(seq), 0) FROM activity) - :keep")
        .bind("cutoff", Instant.now().minus(maxAge))
        .bind("keep", (long) maxRows)
        .then();
  }

  @Override
  public Flux<ActivityEntry> newest(
      final Set<String> types,
      final Set<String> severities,
      @Nullable final Long before,
      final int limit) {
    final List<String> wantedTypes = List.copyOf(types);
    final List<String> wantedSeverities = List.copyOf(severities);
    final StringBuilder sql =
        new StringBuilder(256)
            .append(
                "SELECT seq, at, type, severity, execution_id, CAST(envelope AS text) AS envelope"
                    + " FROM activity WHERE 1 = 1");
    if (before != null) {
      sql.append(" AND seq < :before");
    }
    appendIn(sql, "type", "t", wantedTypes.size());
    appendIn(sql, "severity", "s", wantedSeverities.size());
    sql.append(" ORDER BY seq DESC LIMIT :limit");
    DatabaseClient.GenericExecuteSpec spec = client.sql(sql.toString()).bind("limit", limit);
    if (before != null) {
      spec = spec.bind("before", before);
    }
    spec = bindAll(spec, "t", wantedTypes);
    spec = bindAll(spec, "s", wantedSeverities);
    return spec.map((row, meta) ->
            new ActivityEntry(
                Objects.requireNonNull(row.get("seq", Long.class), "seq"),
                row.get("at", Instant.class),
                row.get("type", String.class),
                row.get("severity", String.class),
                row.get("execution_id", String.class),
                row.get("envelope", String.class)))
        .all();
  }

  private static void appendIn(
      final StringBuilder sql, final String column, final String prefix, final int count) {
    if (count == 0) {
      return;
    }
    final List<String> names = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      names.add(":" + prefix + i);
    }
    sql.append(" AND ").append(column).append(" IN (").append(String.join(", ", names)).append(')');
  }

  private static DatabaseClient.GenericExecuteSpec bindAll(
      final DatabaseClient.GenericExecuteSpec spec,
      final String prefix,
      final List<String> values) {
    DatabaseClient.GenericExecuteSpec next = spec;
    for (int i = 0; i < values.size(); i++) {
      next = next.bind(prefix + i, values.get(i));
    }
    return next;
  }
}
