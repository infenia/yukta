// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for SqlQueryPlugin against an in-memory H2 database. */
class SqlQueryPluginTest {

  /** The plugin under test. */
  private final SqlQueryPlugin plugin = new SqlQueryPlugin();

  /** Keeps the in-memory database alive across connections. */
  private static final String URL = "jdbc:h2:mem:sqltest;DB_CLOSE_DELAY=-1";

  private Message<?> run(final String sql, final Map<String, Object> payload, final Object params) {
    final Map<String, Object> config =
        Map.of("url", URL, "username", "sa", "password", "", "sql", sql, "params", params);
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

  @Test
  void injectionAttemptIsBoundAsPlainValue() {
    run("create table if not exists u(name varchar(40))", Map.of(), List.of());
    run("insert into u values (?)", Map.of("n", "x'); drop table u; --"), List.of("n"));
    final Message<?> out = run("select count(*) c from u", Map.of(), List.of());
    assertThat(((Map<?, ?>) out.payload()).get("count")).isEqualTo(1);
  }

  @Test
  void validateConfig_requiresUrlAndSql() {
    StepVerifier.create(plugin.validateConfig(Map.of("url", URL, "sql", "select 1")))
        .verifyComplete();
    StepVerifier.create(plugin.validateConfig(Map.of("url", URL)))
        .expectError(IllegalArgumentException.class)
        .verify();
    assertThat(plugin.getType()).isEqualTo("SQL_QUERY");
    assertThat(plugin.describe().usagePattern()).contains("params");
  }

  @Test
  void maxRowsAndNonMapPayload() {
    run("create table if not exists m(i int)", Map.of(), List.of());
    run("insert into m values (1)", Map.of(), List.of());
    run("insert into m values (2)", Map.of(), List.of());
    final Map<String, Object> config =
        Map.of(
            "url", URL, "username", "sa", "password", "", "sql", "select i from m", "maxRows", 1);
    final Message<?> out =
        plugin
            .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "text")), config)
            .blockFirst();
    assertThat(((Map<?, ?>) out.payload()).get("count")).isEqualTo(1);
  }
}
