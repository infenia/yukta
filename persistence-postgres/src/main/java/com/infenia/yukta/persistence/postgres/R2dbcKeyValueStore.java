// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.store.KeyValueStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link KeyValueStore} in PostgreSQL ({@code yukta.kv.store-type=postgres}), so the state of
 * {@code KV_STORE} nodes survives a restart and is shared by the servers that use the database. A
 * counter is incremented in one statement, so concurrent increments do not lose updates; the cap on
 * distinct keys is checked in the same statement as the insert but, as in memory, can be overshot
 * slightly by concurrent inserts of different keys.
 */
public class R2dbcKeyValueStore implements KeyValueStore {

  /** Key parameter. */
  private static final String KEY = "k";

  /** Value parameter. */
  private static final String VALUE = "v";

  /** Cap parameter. */
  private static final String MAX = "max";

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the values as JSON. */
  private final ObjectMapper mapper;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the values
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // both are shared Spring beans
  public R2dbcKeyValueStore(final DatabaseClient client, final ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  public Mono<Object> get(final String key) {
    return client
        .sql("SELECT CAST(v AS text) AS v FROM kv_entry WHERE k = :k")
        .bind(KEY, key)
        .map(row -> row.get(VALUE, String.class))
        .one()
        .map(this::read);
  }

  @Override
  public Mono<Object> put(final String key, final Object value, final int maxEntries) {
    return Mono.fromCallable(() -> mapper.writeValueAsString(value))
        .flatMap(
            json ->
                client
                    .sql(
                        """
                        INSERT INTO kv_entry (k, v) SELECT :k, CAST(:v AS jsonb)
                        WHERE EXISTS (SELECT 1 FROM kv_entry WHERE k = :k)
                           OR (SELECT count(*) FROM kv_entry) < :max
                        ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v
                        RETURNING CAST(v AS text) AS v
                        """)
                    .bind(KEY, key)
                    .bind(VALUE, json)
                    .bind(MAX, (long) maxEntries)
                    .map(row -> row.get(VALUE, String.class))
                    .one()
                    .map(this::read)
                    .switchIfEmpty(Mono.error(() -> full(maxEntries))));
  }

  @Override
  public Mono<Number> incr(final String key, final long delta, final int maxEntries) {
    return client
        .sql(
            """
            INSERT INTO kv_entry (k, v) SELECT :k, to_jsonb(CAST(:d AS bigint))
            WHERE EXISTS (SELECT 1 FROM kv_entry WHERE k = :k)
               OR (SELECT count(*) FROM kv_entry) < :max
            ON CONFLICT (k) DO UPDATE SET v = to_jsonb(
              CASE WHEN jsonb_typeof(kv_entry.v) = 'number'
                        AND (kv_entry.v #>> '{}') ~ '^-?[0-9]+$'
                   THEN CAST(kv_entry.v #>> '{}' AS bigint) + CAST(:d AS bigint) END)
            RETURNING CAST(v AS text) AS v
            """)
        .bind(KEY, key)
        .bind("d", delta)
        .bind(MAX, (long) maxEntries)
        .map(row -> row.get(VALUE, String.class))
        .one()
        .map(text -> (Number) read(text))
        .switchIfEmpty(Mono.error(() -> full(maxEntries)))
        .onErrorMap(
            DataIntegrityViolationException.class,
            _ ->
                new IllegalArgumentException(
                    "incr requires the stored value to be a whole number"));
  }

  @Override
  public Mono<Object> remove(final String key) {
    return client
        .sql("DELETE FROM kv_entry WHERE k = :k RETURNING CAST(v AS text) AS v")
        .bind(KEY, key)
        .map(row -> row.get(VALUE, String.class))
        .one()
        .map(this::read);
  }

  private Object read(final String json) {
    return mapper.readValue(json, Object.class);
  }

  private static IllegalStateException full(final int max) {
    return new IllegalStateException("KV store is full (" + max + " entries)");
  }
}
