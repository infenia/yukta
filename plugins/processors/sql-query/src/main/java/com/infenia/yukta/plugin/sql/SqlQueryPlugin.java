// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.sql;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
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

  /** Seconds a statement may run unless {@code timeoutSeconds} says otherwise. */
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

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
            + "- maxRows: cap on returned rows, positive (default 1000).\n"
            + "- timeoutSeconds: statement timeout, positive (default 30).\n"
            + "Emits {rows, count} for queries or {updated} for updates.",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-slate-200 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-blue-100 text-blue-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M3 15h18M9 4v16"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">SQL Query</div>
                        <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                    </div>
                </div>
                """,
                140,
                80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(
            "url", ConfigField.Type.STRING, "JDBC URL (the driver must be on the classpath)"),
        ConfigField.optional("username", ConfigField.Type.STRING, null, "Database user"),
        ConfigField.optional(
            "password", ConfigField.Type.SECRET, null, "Database password (decrypted:<key>)"),
        ConfigField.required("sql", ConfigField.Type.EXPRESSION, "Statement with ? placeholders"),
        ConfigField.optional(
            "params",
            ConfigField.Type.LIST,
            null,
            "Payload fields bound to the placeholders, in order"),
        ConfigField.optional("maxRows", ConfigField.Type.NUMBER, 1000, "Most rows returned"),
        ConfigField.optional(
            "timeoutSeconds", ConfigField.Type.NUMBER, 30, "Statement timeout in seconds"));
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
          positiveInt(config, "maxRows", DEFAULT_MAX_ROWS);
          positiveInt(config, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
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
      statement.setMaxRows(positiveInt(config, "maxRows", DEFAULT_MAX_ROWS));
      statement.setQueryTimeout(positiveInt(config, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS));
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

  private static int positiveInt(
      final Map<String, Object> config, final String key, final int fallback) {
    final Object value = config.get(key);
    int result = fallback;
    if (value != null) {
      if (value instanceof Number number
          && number.doubleValue() == number.intValue()
          && number.intValue() > 0) {
        result = number.intValue();
      } else {
        throw new IllegalArgumentException(key + " must be a positive whole number");
      }
    }
    return result;
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
