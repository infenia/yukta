// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.sql;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.VariableResolver;
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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.CouplingBetweenObjects",
  "PMD.OnlyOneReturn"
}) // ordered rows
public final class SqlQueryPlugin implements ProcessorPlugin {

  /** Rows returned at most per statement unless {@code maxRows} says otherwise. */
  private static final int DEFAULT_MAX_ROWS = 1000;

  /** Seconds a statement may run unless {@code timeoutSeconds} says otherwise. */
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /** Config key. */
  private static final String SQL = "sql";

  /** Config key. */
  private static final String FAILURE_MODE = "failureMode";

  /** Metrics reporter. */
  private final PluginMetricsReporter reporter;

  /** Resolves {@code decrypted:<key>} and {@code ${...}} in the connection settings. */
  private final VariableResolver resolver;

  /** Connection settings after resolution. */
  private record Credentials(String url, @Nullable String username, @Nullable String password) {}

  /** Default constructor without metrics or a secret store. */
  public SqlQueryPlugin() {
    this.reporter = PluginMetricsReporter.NOOP;
    this.resolver = noSecrets();
  }

  /**
   * Constructor without a secret store.
   *
   * @param reporterProvider optional metrics reporter
   */
  public SqlQueryPlugin(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this(reporterProvider, noSecrets());
  }

  /**
   * Spring constructor.
   *
   * @param reporterProvider optional metrics reporter
   * @param resolver resolves secret references and variables in the connection settings
   */
  @Autowired
  public SqlQueryPlugin(
      final ObjectProvider<PluginMetricsReporter> reporterProvider,
      final VariableResolver resolver) {
    this.reporter = reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP);
    this.resolver = resolver;
  }

  private static VariableResolver noSecrets() {
    return new VariableResolver(
        key -> Mono.error(new IllegalStateException("No secret store is configured")));
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Runs a parameterized SQL statement over JDBC for each message.",
        "Configure with:\n"
            + "- url, username, password: JDBC connection (driver must be on the classpath). Each"
            + " may\n"
            + "  use ${...} variables, and password may be decrypted:<key> to read the secret"
            + " store.\n"
            + "- sql: statement using '?' placeholders (required).\n"
            + "- params: payload field names bound to the placeholders, in order.\n"
            + "- maxRows: cap on returned rows, positive (default 1000).\n"
            + "- timeoutSeconds: statement timeout, positive (default 30).\n"
            + "- failureMode: ERROR (default) fails the node; CONTINUE routes to the error port.\n"
            + "Emits {rows, count} for queries or {updated} for updates.",
        Optional.of(new UiDesign("blue", "table_chart", "SQL Query", 140, 80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(
            "url", ConfigField.Type.STRING, "JDBC URL (the driver must be on the classpath)"),
        ConfigField.optional("username", ConfigField.Type.STRING, null, "Database user"),
        ConfigField.optional(
            "password", ConfigField.Type.SECRET, null, "Database password (decrypted:<key>)"),
        ConfigField.required(SQL, ConfigField.Type.STRING, "Statement with ? placeholders"),
        ConfigField.choice(
            FAILURE_MODE, false, "ERROR", "What to do on a SQL error", "ERROR", "CONTINUE"),
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
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return FailureMode.from(config.get(FAILURE_MODE)) == FailureMode.CONTINUE
        ? List.of("default", PluginGuard.ERROR_PORT)
        : getOutputPorts();
  }

  @Override
  public boolean isBlocking() {
    return true;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          ConfigValidation.requiredString(config, "url");
          ConfigValidation.requiredString(config, SQL);
          ConfigValidation.positiveInt(config, "maxRows", DEFAULT_MAX_ROWS);
          ConfigValidation.positiveInt(config, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
          FailureMode.from(config.get(FAILURE_MODE));
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    // ponytail: one connection per message, add a pool (HikariCP) when throughput matters.
    final FailureMode mode = FailureMode.from(config.get(FAILURE_MODE));
    return input.concatMap(
        message ->
            PluginGuard.guard(
                credentials(message, config)
                    .flatMap(
                        credentials ->
                            Mono.<Message<?>>fromCallable(
                                    () -> execute(message, config, credentials))
                                .subscribeOn(Schedulers.boundedElastic())),
                message,
                mode,
                reporter,
                SQL));
  }

  /** The connection settings of one message, with secret references and variables resolved. */
  private Mono<Credentials> credentials(
      final Message<?> message, final Map<String, Object> config) {
    return Mono.zip(
            resolved(config.get("url"), message),
            resolved(config.get("username"), message),
            resolved(config.get("password"), message))
        .map(
            parts ->
                new Credentials(
                    parts.getT1().orElse("null"),
                    parts.getT2().orElse(null),
                    parts.getT3().orElse(null)));
  }

  private Mono<Optional<String>> resolved(final Object value, final Message<?> message) {
    if (value == null) {
      return Mono.just(Optional.empty());
    }
    return resolver
        .resolve(value, message)
        .map(resolvedValue -> Optional.of(resolvedValue.toString()))
        .defaultIfEmpty(Optional.empty());
  }

  private static Message<?> execute(
      final Message<?> message, final Map<String, Object> config, final Credentials credentials)
      throws SQLException {
    final Map<?, ?> payload = message.payload() instanceof Map<?, ?> map ? map : Map.of();
    try (Connection connection =
            DriverManager.getConnection(
                credentials.url(), credentials.username(), credentials.password());
        PreparedStatement statement =
            connection.prepareStatement(String.valueOf(config.get(SQL)))) {
      statement.setMaxRows(ConfigValidation.positiveInt(config, "maxRows", DEFAULT_MAX_ROWS));
      statement.setQueryTimeout(
          ConfigValidation.positiveInt(config, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS));
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
