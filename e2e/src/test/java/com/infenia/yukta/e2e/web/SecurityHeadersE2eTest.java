// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.security.ApiRole;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/** {@code SecurityHeadersFilter}: the hardening headers are on every response, errors included. */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class SecurityHeadersE2eTest {

  private static void assertHardened(final WebTestClient.ResponseSpec response) {
    response
        .expectHeader()
        .valueEquals("X-Content-Type-Options", "nosniff")
        .expectHeader()
        .valueEquals("Referrer-Policy", "no-referrer")
        .expectHeader()
        .doesNotExist("Strict-Transport-Security");
  }

  @Test
  void anApiResponseHasThem() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    assertHardened(api.client().get().uri("/api/sessions").exchange());
  }

  @Test
  void staticAssetHasThem() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    assertHardened(api.client().get().uri("/").exchange().expectStatus().isOk());
  }

  @Test
  void anErrorHasThem() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    assertHardened(
        api.client().get().uri("/api/e2e-no-such-route").exchange().expectStatus().isNotFound());
  }

  @Test
  void refusalHasThem() {
    final Api api = Api.toServer(com.infenia.yukta.e2e.support.YuktaServer.baseUrl(), null);

    assertHardened(api.client().get().uri("/actuator/health").exchange());
  }

  @Test
  void hstsIsAddedBehindProxyThatSaysHttps() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    api.client()
        .get()
        .uri("/api/sessions")
        .header("X-Forwarded-Proto", "https")
        .exchange()
        .expectHeader()
        .valueEquals("Strict-Transport-Security", "max-age=31536000");
  }
}
