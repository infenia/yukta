// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.infenia.yukta.dto.response.ConfigValue;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import reactor.test.StepVerifier;

/** Tests for {@link ConfigController}. */
@NoArgsConstructor
class ConfigControllerTest {

  private static final String MASK = "******";

  private static final String PORT = "server.port";

  /** A source that resolves a value but does not report that it holds the key. */
  private static final class Opaque extends PropertySource<String> {
    private Opaque() {
      super("opaque", "x");
    }

    @Override
    public Object getProperty(final String name) {
      return "server.address".equals(name) ? "all-interfaces" : null;
    }

    @Override
    public boolean containsProperty(final String name) {
      return false;
    }
  }

  private static StandardEnvironment environment() {
    final StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "Config resource 'class path resource [application.yaml]'",
                Map.of(
                    PORT,
                    "9858",
                    "yukta.api-keys[0].key",
                    "hunter2",
                    "yukta.input.token-secret",
                    "s3cret",
                    "spring.r2dbc.url",
                    "r2dbc:postgresql://yukta:pw123@db:5432/yukta",
                    "yukta.idempotency-max-keys",
                    "100")));
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "configurationProperties", Map.of("yukta.agents.enabled", "false")));
    env.getPropertySources()
        .addFirst(new MapPropertySource("commandLineArgs", Map.of(PORT, "7000")));
    env.getPropertySources()
        .addFirst(
            new MapPropertySource("systemProperties", Map.of("yukta.rate-limit-per-minute", "5")));
    env.getPropertySources()
        .addFirst(
            new MapPropertySource("systemEnvironment", Map.of("yukta.plugins.enabled", "all")));
    env.getPropertySources()
        .addFirst(new MapPropertySource("somewhere", Map.of("yukta.cache.ttl", "5m")));
    env.getPropertySources().addLast(new Opaque());
    return env;
  }

  private static List<ConfigValue> show(final List<String> keys) {
    return new ConfigController(environment()).show(keys).blockOptional().orElseThrow().data();
  }

  @Test
  void show_returnsTheValueAndWhereItComesFromInTheOrderAsked() {
    final List<ConfigValue> values =
        show(
            List.of(
                "yukta.plugins.enabled",
                "yukta.rate-limit-per-minute",
                PORT,
                "yukta.cache.ttl",
                "yukta.api-keys[0].key"));

    assertThat(values)
        .extracting(ConfigValue::key, ConfigValue::value, ConfigValue::source)
        .containsExactly(
            tuple("yukta.plugins.enabled", "all", "environment variable"),
            tuple("yukta.rate-limit-per-minute", "5", "system property"),
            tuple(PORT, "7000", "argument"),
            tuple("yukta.cache.ttl", "5m", "other"),
            tuple("yukta.api-keys[0].key", MASK, "configuration file"));
  }

  @Test
  void show_labelsLegacyConfigurationFileSource() {
    final var env = environment();
    env.getPropertySources()
        .addFirst(new MapPropertySource("applicationConfig: [x]", Map.of("yukta.y", "1")));
    assertThat(
            new ConfigController(env)
                .show(List.of("yukta.y"))
                .blockOptional()
                .orElseThrow()
                .data()
                .getFirst()
                .source())
        .isEqualTo("configuration file");
  }

  @Test
  void show_saysDefaultWithNoValueWhenNothingSetsTheKey() {
    final ConfigValue value = show(List.of("yukta.execution.timeout")).getFirst();

    assertThat(value.value()).isNull();
    assertThat(value.source()).isEqualTo("default");
  }

  @Test
  void show_hidesSecretsAndUrlPasswordsButNotLookAlikes() {
    final List<ConfigValue> values =
        show(List.of("yukta.input.token-secret", "spring.r2dbc.url", "yukta.idempotency-max-keys"));

    assertThat(values.get(0).value()).isEqualTo(MASK);
    assertThat(values.get(1).value()).isEqualTo("r2dbc:postgresql://yukta:******@db:5432/yukta");
    assertThat(values.get(2).value()).isEqualTo("100");
    assertThat(show(List.of(PORT)).getFirst().value()).isEqualTo("7000");
  }

  @Test
  void show_skipsTheBootAliasSourceAndFallsBackToOtherForAnOpaqueOne() {
    // only the alias source holds it: the value shows, the source cannot be named
    final ConfigValue alias = show(List.of("yukta.agents.enabled")).getFirst();
    assertThat(alias.value()).isEqualTo("false");
    assertThat(alias.source()).isEqualTo("other");
    final ConfigValue opaque = show(List.of("server.address")).getFirst();
    assertThat(opaque.value()).isEqualTo("all-interfaces");
    assertThat(opaque.source()).isEqualTo("other");
  }

  @Test
  void show_asksEachKeyOnce() {
    assertThat(show(List.of(PORT, PORT))).hasSize(1);
  }

  @Test
  void show_refusesKeyOutsideTheKnownPrefixes() {
    StepVerifier.create(new ConfigController(environment()).show(List.of("java.home")))
        .expectErrorSatisfies(error ->
            assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.home"))
        .verify();
  }

  @Test
  void show_refusesTooLongKeyAndTooManyKeys() {
    final var controller = new ConfigController(environment());

    StepVerifier.create(controller.show(List.of("yukta." + "x".repeat(200))))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            controller.show(IntStream.range(0, 501).mapToObj(i -> "yukta.k" + i).toList()))
        .expectErrorMessage("At most 500 keys per request")
        .verify();
  }
}
