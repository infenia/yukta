// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.security.ApiAuthenticator.Scope;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;

/** Tests for {@link ApiKeyAuthenticator}. */
@NoArgsConstructor
class ApiKeyAuthenticatorTest {

  @Test
  void single_usesTheKeyOnBothSurfaces() {
    final ApiKeyAuthenticator auth = ApiKeyAuthenticator.single("k");
    final HttpHeaders good = new HttpHeaders();
    good.set("X-API-Key", "k");

    assertThat(auth.isOpen(Scope.REST)).isFalse();
    assertThat(auth.authenticate(Scope.REST, good)).isNotNull();
    assertThat(auth.authenticate(Scope.MCP, good)).isNotNull();
    assertThat(auth.authenticate(Scope.MCP, new HttpHeaders())).isNull();
    assertThat(auth.named()).isFalse();
    assertThat(ApiKeyAuthenticator.single("").isOpen(Scope.MCP)).isTrue();
  }

  @Test
  void read_givesMcpItsOwnKeyAndNamedKeysAreReported() {
    final ApiKeyAuthenticator auth =
        ApiKeyAuthenticator.read(
            new MockEnvironment()
                .withProperty("yukta.mcp.api-key", "m")
                .withProperty("yukta.api-keys[0].name", "bob")
                .withProperty("yukta.api-keys[0].key", "b"));
    final HttpHeaders mcp = new HttpHeaders();
    mcp.set("X-API-Key", "m");

    assertThat(auth.named()).isTrue();
    assertThat(auth.authenticate(Scope.MCP, mcp)).isNotNull();
    assertThat(auth.authenticate(Scope.REST, mcp)).isNull();
    assertThat(auth.isOpen(Scope.REST)).isFalse();
  }
}
