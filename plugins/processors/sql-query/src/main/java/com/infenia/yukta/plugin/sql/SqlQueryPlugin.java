// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.sql;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs one parameterized JDBC statement per message. Values are only ever bound as {@code ?}
 * parameters, never concatenated into the SQL.
 */
@Component
@SuppressWarnings({
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidInstantiatingObjectsInLoops"
}) // ordered rows
public final class SqlQueryPlugin implements ProcessorPlugin {

  /** Rows returned at most per statement unless {@code maxRows} says otherwise. */
  private static final int DEFAULT_MAX_ROWS = 1000;

  /** Default constructor. */
  public SqlQueryPlugin() {
    super();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Runs a parameterized SQL statement over JDBC for each message.",
        "Configure with:\n"
            + "- url, username, password: JDBC connection (driver must be on the classpath).\n"
            + "- sql: statement using '?' placeholders (required).\n"
            + "- params: payload field names bound to the placeholders, in order.\n"
            + "- maxRows: cap on returned rows (default 1000).\n"
            + "Emits {rows, count} for queries or {updated} for updates.",
        Optional.empty());
  }

  @Override
  public String getType() {
    return "SQL_QUERY";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          for (final String key : List.of("url", "sql")) {
            if (config.get(key) == null || String.valueOf(config.get(key)).isBlank()) {
              throw new IllegalArgumentException(key + " is required");
            }
          }
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    // ponytail: one connection per message, add a pool (HikariCP) when throughput matters.
    return input.concatMap(
        message ->
            Mono.fromCallable(() -> execute(message, config))
                .subscribeOn(Schedulers.boundedElastic()));
  }

  private static Message<?> execute(final Message<?> message, final Map<String, Object> config)
      throws SQLException {
    final Map<?, ?> payload = message.payload() instanceof Map<?, ?> map ? map : Map.of();
    try (Connection connection =
            DriverManager.getConnection(
                String.valueOf(config.get("url")),
                (String) config.get("username"),
                (String) config.get("password"));
        PreparedStatement statement =
            connection.prepareStatement(String.valueOf(config.get("sql")))) {
      statement.setMaxRows(
          config.get("maxRows") instanceof Number max ? max.intValue() : DEFAULT_MAX_ROWS);
      final List<?> params = config.get("params") instanceof List<?> list ? list : List.of();
      for (int i = 0; i < params.size(); i++) {
        statement.setObject(i + 1, payload.get(String.valueOf(params.get(i))));
      }
      final Map<String, Object> result = new LinkedHashMap<>();
      if (statement.execute()) {
        final List<Map<String, Object>> rows = rows(statement.getResultSet());
        result.put("rows", rows);
        result.put("count", rows.size());
      } else {
        result.put("updated", statement.getUpdateCount());
      }
      return message.withPayload(result).withSourcePort("default");
    }
  }

  private static List<Map<String, Object>> rows(final ResultSet resultSet) throws SQLException {
    final ResultSetMetaData meta = resultSet.getMetaData();
    final List<Map<String, Object>> rows = new ArrayList<>();
    while (resultSet.next()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 1; i <= meta.getColumnCount(); i++) {
        row.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), resultSet.getObject(i));
      }
      rows.add(row);
    }
    return rows;
  }
}
