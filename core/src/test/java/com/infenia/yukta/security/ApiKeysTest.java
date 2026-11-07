// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;

/** Tests for the behaviour described by {@link ApiKeys}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ApiKeysTest {

  /** SHA-256 of {@code secret}. */
  private static final String SECRET_HASH =
      "sha256:2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b";

  @Test
  void read_collectsNamedKeys() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].key", "bare")
            .withProperty("yukta.api-keys[0].sessions", "*")
            .withProperty("yukta.api-keys[1].name", "team")
            .withProperty("yukta.api-keys[1].key", SECRET_HASH)
            .withProperty("yukta.api-keys[1].role", "OPERATOR")
            .withProperty("yukta.api-keys[1].sessions", "a,b");

    final ApiKeys keys = ApiKeys.read(env);

    assertThat(keys.find("bare").name()).isEqualTo("unnamed");
    assertThat(keys.find("bare").role()).isEqualTo(ApiRole.VIEWER);
    final var team = keys.find("secret");
    assertThat(team.name()).isEqualTo("team");
    assertThat(team.role()).isEqualTo(ApiRole.OPERATOR);
    assertThat(team.reaches("a")).isTrue();
    assertThat(team.reaches("c")).isFalse();
    assertThat(team.agent()).isFalse();
  }

  @Test
  void read_marksAnAgentKey() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "claude")
            .withProperty("yukta.api-keys[0].key", "agent-secret")
            .withProperty("yukta.api-keys[0].role", "OPERATOR")
            .withProperty("yukta.api-keys[0].sessions", "dogfood")
            .withProperty("yukta.api-keys[0].agent", "true");

    final var match = ApiKeys.read(env).find("agent-secret");

    assertThat(match.agent()).isTrue();
    assertThat(match.reaches("dogfood")).isTrue();
  }

  @Test
  void read_ignoresBlankKeys() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].key", " ")
            .withProperty("yukta.api-keys[0].sessions", "*");

    assertThat(ApiKeys.read(new MockEnvironment()).isEmpty()).isTrue();
    assertThat(ApiKeys.read(env).isEmpty()).isTrue();
  }

  @Test
  void read_refusesMalformedDigest() {
    final var tooShort =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "h")
            .withProperty("yukta.api-keys[0].key", "sha256:abc")
            .withProperty("yukta.api-keys[0].sessions", "*");
    final var nonHex =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "h")
            .withProperty("yukta.api-keys[0].key", "sha256:" + "z".repeat(64))
            .withProperty("yukta.api-keys[0].sessions", "*");
    final var empty =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "h")
            .withProperty("yukta.api-keys[0].key", "sha256:")
            .withProperty("yukta.api-keys[0].sessions", "*");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.read(tooShort))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'h'");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.read(nonHex))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64 hexadecimal");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.read(empty))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64 hexadecimal");
  }

  @Test
  void read_refusesTheSameSecretTwice() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "first")
            .withProperty("yukta.api-keys[0].key", "same-secret-same-secret-same-secret-1")
            .withProperty("yukta.api-keys[0].sessions", "*")
            .withProperty("yukta.api-keys[1].name", "ops")
            .withProperty("yukta.api-keys[1].key", "same-secret-same-secret-same-secret-1")
            .withProperty("yukta.api-keys[1].sessions", "*");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.read(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ops");
  }

  @Test
  void hash_failsForAnUnknownAlgorithm() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.hash("NOPE", "x"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void presented_readsBearerThenHeader() {
    final var bearer = new HttpHeaders();
    bearer.set(HttpHeaders.AUTHORIZATION, "Bearer abc");
    final var header = new HttpHeaders();
    header.set("X-API-Key", "xyz");
    final var basic = new HttpHeaders();
    basic.set(HttpHeaders.AUTHORIZATION, "Basic abc");

    final var lower = new HttpHeaders();
    lower.set(HttpHeaders.AUTHORIZATION, "bearer abc");
    final var upper = new HttpHeaders();
    upper.set(HttpHeaders.AUTHORIZATION, "BEARER abc");

    assertThat(ApiKeys.presented(lower)).isEqualTo("abc");
    assertThat(ApiKeys.presented(upper)).isEqualTo("abc");
    assertThat(ApiKeys.presented(bearer)).isEqualTo("abc");
    assertThat(ApiKeys.presented(header)).isEqualTo("xyz");
    assertThat(ApiKeys.presented(basic)).isEmpty();
  }

  @Test
  void anExpiredKeyNoLongerMatches() {
    final var keys =
        ApiKeys.read(
            new MockEnvironment()
                .withProperty("yukta.api-keys[0].name", "old")
                .withProperty("yukta.api-keys[0].key", "old-key")
                .withProperty("yukta.api-keys[0].sessions", "*")
                .withProperty("yukta.api-keys[0].expires", "2020-01-01T00:00:00Z")
                .withProperty("yukta.api-keys[1].name", "new")
                .withProperty("yukta.api-keys[1].key", "new-key")
                .withProperty("yukta.api-keys[1].sessions", "*")
                .withProperty("yukta.api-keys[1].expires", "2999-01-01T00:00:00Z")
                .withProperty("yukta.api-keys[2].name", "forever")
                .withProperty("yukta.api-keys[2].key", "forever-key")
                .withProperty("yukta.api-keys[2].sessions", "*"));

    assertThat(keys.find("old-key")).isNull();
    assertThat(keys.find("new-key")).isNotNull();
    assertThat(keys.find("forever-key")).isNotNull();
    assertThat(keys.find("other")).isNull();
  }
}
