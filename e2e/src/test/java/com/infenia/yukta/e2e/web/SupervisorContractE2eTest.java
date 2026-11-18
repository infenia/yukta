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

/** {@code GET /api/supervisor-contract}: the operations an agent supervisor may use. */
@E2eTest
@NoArgsConstructor
class SupervisorContractE2eTest {

  @Test
  void theContractNamesItsVersionAndOperations() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    final JsonNode contract =
        api.data(api.client().get().uri("/api/supervisor-contract").exchange());

    assertThat(contract.path("version").asInt()).isPositive();
    assertThat(contract.path("operations").path("read").isArray()).isTrue();
    assertThat(contract.path("operations").path("act").isArray()).isTrue();
    assertThat(contract.path("operations").has("directives")).isTrue();
  }
}
