// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.security.ApiRole;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code GET /api/ai/backends} lists the kinds of AI backend and the options each takes. */
@E2eTest
@NoArgsConstructor
class AiBackendE2eTest {

  @Test
  void everyBackendHasKindDescriptionAndOptions() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    final JsonNode backends = api.data(api.client().get().uri("/api/ai/backends").exchange());

    assertThat(backends.isArray()).isTrue();
    assertThat(backends).isNotEmpty();
    for (final JsonNode backend : backends) {
      assertThat(backend.path("kind").asString()).isNotBlank();
      assertThat(backend.path("description").asString()).isNotBlank();
      assertThat(backend.path("options").isArray()).isTrue();
    }
  }
}
