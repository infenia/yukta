// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Tests for the session reach of keys read by {@link ApiKeys}. */
@NoArgsConstructor
class ApiKeysSessionsTest {

  @Test
  void starSessionsKeyReachesEverySessionOnPurpose() {
    final var keys =
        ApiKeys.read(
            new MockEnvironment()
                .withProperty("yukta.api-keys[0].name", "everything")
                .withProperty("yukta.api-keys[0].key", "everything-key")
                .withProperty("yukta.api-keys[0].role", "OPERATOR")
                .withProperty("yukta.api-keys[0].sessions", "*"),
            null);

    final var match = keys.find("everything-key");
    assertThat(match.sessions()).isEmpty();
    assertThat(match.reaches("any-session")).isTrue();
    assertThat(match.role()).isEqualTo(ApiRole.OPERATOR);
  }

  @Test
  void nonAdminKeyWithoutSessionsIsRefusedButAdminReachesEverySession() {
    final var viewer =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "viewer")
            .withProperty("yukta.api-keys[0].key", "viewer-key");
    assertThatThrownBy(() -> ApiKeys.read(viewer, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("viewer");

    final var admin =
        ApiKeys.read(
            new MockEnvironment()
                .withProperty("yukta.api-keys[0].name", "admin")
                .withProperty("yukta.api-keys[0].key", "admin-key")
                .withProperty("yukta.api-keys[0].role", "ADMIN"),
            null);
    assertThat(admin.find("admin-key").reaches("any-session")).isTrue();
  }
}
