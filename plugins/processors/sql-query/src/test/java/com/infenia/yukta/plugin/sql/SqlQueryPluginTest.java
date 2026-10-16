// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for SqlQueryPlugin against an in-memory H2 database. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class SqlQueryPluginTest {

  /** Test literal sql. */
  private static final String SQL_KEY = "sql";

  /** Config key. */
  private static final String USER_KEY = "username";

  /** Config key. */
  private static final String PASS_KEY = "password";

  /** Config key. */
  private static final String URL_KEY = "url";

  /** The plugin under test. */
  private final SqlQueryPlugin plugin = new SqlQueryPlugin();

  /** Keeps the in-memory database alive across connections. */
  private static final String URL = "jdbc:h2:mem:sqltest;DB_CLOSE_DELAY=-1";

  private Message<?> run(final String sql, final Map<String, Object> payload, final Object params) {
    final Map<String, Object> config =
        Map.of(URL_KEY, URL, USER_KEY, "sa", PASS_KEY, "", SQL_KEY, sql, "params", params);
    final Message<?> input = DefaultMessage.create(UUID.randomUUID(), payload);
    return plugin.process(Flux.just(input), config).blockFirst();
  }

  @Test
  void insertThenSelect_bindsParamsFromPayload() {
    run("create table if not exists t(id int, name varchar(20))", Map.of(), List.of());
    final Message<?> inserted =
        run("insert into t values (?, ?)", Map.of("id", 1, "name", "ann"), List.of("id", "name"));
    assertThat(((Map<?, ?>) inserted.payload()).get("updated")).isEqualTo(1);

    final Message<?> selected =
        run("select id, name from t where id = ?", Map.of("id", 1), List.of("id"));
    assertThat(selected.payload())
        .isEqualTo(Map.of("rows", List.of(Map.of("id", 1, "name", "ann")), "count", 1));
  }

  /** A plugin whose secret store holds one secret, {@code db-pass}. */
  private static SqlQueryPlugin pluginWithSecrets() {
    return new SqlQueryPlugin(
        new StaticListableBeanFactory().getBeanProvider(PluginMetricsReporter.class),
        new VariableResolver(key -> "db-pass".equals(key) ? Mono.just("pw") : Mono.empty()));
  }

  @Test
  void passwordCanBeSecretReference() {
    run("create user if not exists app password 'pw' admin", Map.of(), List.of());
    final Map<String, Object> config =
        Map.of(
            URL_KEY, URL, USER_KEY, "app", PASS_KEY, "decrypted:db-pass", SQL_KEY, "select 1 as x");

    final Message<?> out =
        pluginWithSecrets()
            .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of())), config)
            .blockFirst();

    assertThat(out.payload()).isEqualTo(Map.of("rows", List.of(Map.of("x", 1)), "count", 1));
  }

  @Test
  void anUnknownSecretFailsTheMessageInsteadOfConnectingWithItsName() {
    final Map<String, Object> config =
        Map.of(
            URL_KEY, URL, USER_KEY, "sa", PASS_KEY, "decrypted:missing", SQL_KEY, "select 1 as x");

    StepVerifier.create(
            pluginWithSecrets()
                .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of())), config))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("Secret not found"))
        .verify();
  }

  @Test
  void usernameAndUrlMayUseVariablesToo() {
    final Map<String, Object> config =
        Map.of(
            URL_KEY,
            "${payload.url}",
            USER_KEY,
            "${payload.user}",
            PASS_KEY,
            "",
            SQL_KEY,
            "select 1 as x");

    final Message<?> out =
        pluginWithSecrets()
            .process(
                Flux.just(
                    DefaultMessage.create(UUID.randomUUID(), Map.of("url", URL, "user", "sa"))),
                config)
            .blockFirst();

    assertThat(((Map<?, ?>) out.payload()).get("count")).isEqualTo(1);
  }

  @Test
  void injectionAttemptIsBoundAsPlainValue() {
    run("create table if not exists u(name varchar(40))", Map.of(), List.of());
    run("insert into u values (?)", Map.of("n", "x'); drop table u; --"), List.of("n"));
    final Message<?> out = run("select count(*) c from u", Map.of(), List.of());
    assertThat(((Map<?, ?>) out.payload()).get("count")).isEqualTo(1);
  }

  @Test
  void validateConfig_requiresUrlAndSql() {
    StepVerifier.create(plugin.validateConfig(Map.of(URL_KEY, URL, SQL_KEY, "select 1")))
        .verifyComplete();
    StepVerifier.create(plugin.validateConfig(Map.of(URL_KEY, URL)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(plugin.validateConfig(Map.of(URL_KEY, URL, SQL_KEY, " ")))
        .expectError(IllegalArgumentException.class)
        .verify();
    assertThat(plugin.getType()).isEqualTo("SQL_QUERY");
    assertThat(plugin.describe().usagePattern()).contains("params");
  }

  @Test
  void nonPositiveLimitsAreRejected() {
    for (final String key : List.of("maxRows", "timeoutSeconds")) {
      for (final Object bad : List.of(0, -1, 1.5, "x")) {
        StepVerifier.create(
                plugin.validateConfig(Map.of(URL_KEY, URL, SQL_KEY, "select 1", key, bad)))
            .expectError(IllegalArgumentException.class)
            .verify();
      }
    }
  }

  @Test
  void maxRowsAndNonMapPayload() {
    run("create table if not exists m(i int)", Map.of(), List.of());
    run("insert into m values (1)", Map.of(), List.of());
    run("insert into m values (2)", Map.of(), List.of());
    final Map<String, Object> config =
        Map.of(
            URL_KEY, URL, USER_KEY, "sa", PASS_KEY, "", SQL_KEY, "select i from m", "maxRows", 1);
    final Message<?> out =
        plugin
            .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "text")), config)
            .blockFirst();
    assertThat(((Map<?, ?>) out.payload()).get("count")).isEqualTo(1);
  }

  @Test
  void describe_pluginProvidesNodeDesign() {
    assertThat(plugin.describe().uiDesign())
        .hasValueSatisfying(
            design -> {
              assertThat(design.html()).contains("{{nodeId}}");
              assertThat(design.width()).isEqualTo(140);
              assertThat(design.height()).isEqualTo(80);
            });
  }

  @Test
  void continueModeRoutesSqlErrorsToErrorPort() {
    final Map<String, Object> config =
        Map.of(
            URL_KEY,
            URL,
            USER_KEY,
            "sa",
            PASS_KEY,
            "",
            SQL_KEY,
            "select * from missing_tbl",
            "failureMode",
            "CONTINUE");
    final Message<?> out =
        plugin
            .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of())), config)
            .blockFirst();
    assertThat(out.sourcePort()).isEqualTo("error");
    assertThat(out.failureReason()).isEqualTo("JdbcSQLSyntaxErrorException");
    assertThat(plugin.getOutputPorts(config)).containsExactly("default", "error");
  }

  @Test
  void errorModeStillFails() {
    final Map<String, Object> config =
        Map.of(URL_KEY, URL, USER_KEY, "sa", PASS_KEY, "", SQL_KEY, "select * from missing_tbl");
    StepVerifier.create(
            plugin.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of())), config))
        .expectError()
        .verify();
    assertThat(plugin.isBlocking()).isTrue();
  }

  private static ObjectProvider<PluginMetricsReporter> provider(final PluginMetricsReporter value) {
    final StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean("reporter", value);
    return factory.getBeanProvider(PluginMetricsReporter.class);
  }

  @Test
  void springConstructorReportsMetrics() {
    final List<String> counts = new ArrayList<>();
    final SqlQueryPlugin reporting =
        new SqlQueryPlugin(provider((node, metric, tag) -> counts.add(metric + ":" + tag)));
    final Map<String, Object> config =
        Map.of(URL_KEY, URL, USER_KEY, "sa", PASS_KEY, "", SQL_KEY, "select 1");
    reporting
        .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of())), config)
        .blockFirst();
    assertThat(counts).containsExactly("sql:OK");
    final var empty = new StaticListableBeanFactory().getBeanProvider(PluginMetricsReporter.class);
    assertThat(new SqlQueryPlugin(empty).getOutputPorts(Map.of())).containsExactly("default");
  }
}
