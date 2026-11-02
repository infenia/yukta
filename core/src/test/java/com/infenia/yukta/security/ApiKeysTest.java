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
  void single_isEmptyWithoutKey_andAdminWithOne() {
    assertThat(ApiKeys.single("").isEmpty()).isTrue();
    final var match = ApiKeys.single("k").find("k");
    assertThat(match.role()).isEqualTo(ApiRole.ADMIN);
    assertThat(match.name()).isEqualTo("default");
    assertThat(match.reaches("any")).isTrue();
    assertThat(ApiKeys.single("k").named()).isFalse();
    assertThat(ApiKeys.single("k").find("other")).isNull();
    assertThat(ApiKeys.single("short").isEmpty()).isFalse();
  }

  @Test
  void read_collectsRestMcpAndNamedKeys() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-key", "rest")
            .withProperty("yukta.mcp.api-key", "mcp")
            .withProperty("yukta.api-keys[0].key", "bare")
            .withProperty("yukta.api-keys[0].sessions", "*")
            .withProperty("yukta.api-keys[1].name", "team")
            .withProperty("yukta.api-keys[1].key", SECRET_HASH)
            .withProperty("yukta.api-keys[1].role", "OPERATOR")
            .withProperty("yukta.api-keys[1].sessions", "a,b");

    final ApiKeys keys = ApiKeys.read(env, "yukta.mcp.api-key");

    assertThat(keys.named()).isTrue();
    assertThat(keys.find("rest").name()).isEqualTo("default");
    assertThat(keys.find("mcp").name()).isEqualTo("mcp");
    assertThat(keys.find("bare").name()).isEqualTo("unnamed");
    assertThat(keys.find("bare").role()).isEqualTo(ApiRole.VIEWER);
    final var team = keys.find("secret");
    assertThat(team.name()).isEqualTo("team");
    assertThat(team.role()).isEqualTo(ApiRole.OPERATOR);
    assertThat(team.reaches("a")).isTrue();
    assertThat(team.reaches("c")).isFalse();
  }

  @Test
  void read_ignoresBlankKeysAndTheMcpPropertyWhenNotAsked() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-key", " ")
            .withProperty("yukta.mcp.api-key", "m");

    assertThat(ApiKeys.read(new MockEnvironment(), "yukta.mcp.api-key").isEmpty()).isTrue();
    assertThat(ApiKeys.read(env, null).isEmpty()).isTrue();
    assertThat(ApiKeys.read(env, "yukta.mcp.api-key").isEmpty()).isFalse();
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

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.read(tooShort, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'h'");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.read(nonHex, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64 hexadecimal");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.read(empty, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64 hexadecimal");
  }

  @Test
  void read_refusesTheSameSecretTwice() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-key", "same-secret-same-secret-same-secret-1")
            .withProperty("yukta.api-keys[0].name", "ops")
            .withProperty("yukta.api-keys[0].key", "same-secret-same-secret-same-secret-1")
            .withProperty("yukta.api-keys[0].sessions", "*");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApiKeys.read(env, null))
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
                .withProperty("yukta.api-keys[2].sessions", "*"),
            null);

    assertThat(keys.find("old-key")).isNull();
    assertThat(keys.find("new-key")).isNotNull();
    assertThat(keys.find("forever-key")).isNotNull();
    assertThat(keys.find("other")).isNull();
  }
}
