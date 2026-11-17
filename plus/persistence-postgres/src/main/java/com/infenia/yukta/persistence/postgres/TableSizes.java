// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * The size on disk of store tables ({@code pg_total_relation_size}: rows, out-of-line values and
 * indexes), the same on every server and after a restart. A read answers the last known value and,
 * when that is older than a minute, asks the database in the background, so a metrics scrape never
 * waits for it. Deleted rows count until PostgreSQL vacuums them.
 */
@Slf4j
public final class TableSizes {

  /** The tables that grow with use. */
  public static final List<String> TABLES =
      List.of(
          "execution_log",
          "node_checkpoint",
          "workflow_definition",
          "workflow_definition_revision",
          "session_config",
          "input_request",
          "notifications");

  /** How old a size may be before it is read again. */
  private static final long MAX_AGE_NANOS = Duration.ofMinutes(1).toNanos();

  /** Runs the query. */
  private final DatabaseClient client;

  /** The tables read. */
  private final List<String> tables;

  /** The last known sizes, in bytes. */
  private final Map<String, AtomicLong> bytes = new ConcurrentHashMap<>();

  /** When the sizes were last asked for, by {@link System#nanoTime()}. */
  private final AtomicLong askedAt = new AtomicLong(System.nanoTime() - 2 * MAX_AGE_NANOS);

  /** A read is going. */
  private final AtomicBoolean reading = new AtomicBoolean();

  /**
   * Creates the reader.
   *
   * @param client runs the query
   * @param tables the tables to read
   */
  public TableSizes(final DatabaseClient client, final List<String> tables) {
    this.client = client;
    this.tables = List.copyOf(tables);
    tables.forEach(table -> bytes.put(table, new AtomicLong()));
  }

  /**
   * Registers {@code yukta.store.table.bytes} with a {@code table} tag for each table.
   *
   * @param meters where the gauges go
   */
  public void register(final MeterRegistry meters) {
    tables.forEach(table ->
        Gauge.builder("yukta.store.table.bytes", this, sizes -> sizes.bytes(table))
            .description("Size on disk of a PostgreSQL store table, read at most once a minute")
            .tag("table", table)
            .baseUnit("bytes")
            .register(meters));
  }

  /**
   * The last known size of {@code table}; starts a new read when it is stale.
   *
   * @param table one of the tables
   * @return the size in bytes, 0 until the first read has answered
   */
  public long bytes(final String table) {
    final long now = System.nanoTime();
    if (now - askedAt.get() >= MAX_AGE_NANOS && reading.compareAndSet(false, true)) {
      askedAt.set(now);
      client
          .sql(
              "SELECT t, coalesce(pg_total_relation_size(to_regclass(t)), 0) AS size"
                  + " FROM unnest(CAST(:tables AS text[])) AS t")
          .bind("tables", tables.toArray(String[]::new))
          .map(row -> Map.entry(row.get("t", String.class), row.get("size", Long.class)))
          .all()
          .doFinally(_ -> reading.set(false))
          .subscribe(
              entry -> bytes.get(entry.getKey()).set(entry.getValue()),
              error -> log.atWarn().setCause(error).log("Could not read the store table sizes"));
    }
    return bytes.get(table).get();
  }
}
