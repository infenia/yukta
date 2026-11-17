// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Proves the harness: the server is up and the API answers an authenticated call. */
@E2eTest
@NoArgsConstructor
class HealthE2eTest {

  @Test
  void healthIsUp(final Api api) {
    final JsonNode health =
        api.client()
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(JsonNode.class)
            .getResponseBody()
            .blockFirst();
    assertThat(health.path("status").asString()).isEqualTo("UP");
  }

  @Test
  void adminListsSessions(final Api api) {
    assertThat(api.data(api.client().get().uri("/api/sessions").exchange())).isNotNull();
  }
}
