// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.security.ApiRole;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** {@code GET /api/workers}: the remote workers (an empty list when there are none). */
@E2eTest
@NoArgsConstructor
class WorkersE2eTest {

  @Test
  void workersAreListed() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    assertThat(api.data(api.client().get().uri("/api/workers").exchange()).isArray()).isTrue();
  }
}
