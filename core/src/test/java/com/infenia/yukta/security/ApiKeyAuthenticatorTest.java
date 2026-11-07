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
  void read_usesTheNamedKeysOnBothSurfaces() {
    final ApiKeyAuthenticator auth =
        ApiKeyAuthenticator.read(
            new MockEnvironment()
                .withProperty("yukta.api-keys[0].name", "bob")
                .withProperty("yukta.api-keys[0].key", "b")
                .withProperty("yukta.api-keys[0].sessions", "*"));
    final HttpHeaders good = new HttpHeaders();
    good.set("X-API-Key", "b");

    assertThat(auth.isOpen(Scope.REST)).isFalse();
    assertThat(auth.authenticate(Scope.REST, good)).isNotNull();
    assertThat(auth.authenticate(Scope.MCP, good)).isNotNull();
    assertThat(auth.authenticate(Scope.MCP, new HttpHeaders())).isNull();
  }

  @Test
  void read_withoutKeysLeavesBothSurfacesOpen() {
    final ApiKeyAuthenticator auth = ApiKeyAuthenticator.read(new MockEnvironment());

    assertThat(auth.isOpen(Scope.REST)).isTrue();
    assertThat(auth.isOpen(Scope.MCP)).isTrue();
  }
}
