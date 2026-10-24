// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Tests for the behaviour described by {@link ApiRole}. */
@NoArgsConstructor
class ApiRoleTest {

  @Test
  void viewer_onlyReads() {
    assertThat(ApiRole.VIEWER.allows(HttpMethod.GET, "/api/x")).isTrue();
    assertThat(ApiRole.VIEWER.allows(HttpMethod.HEAD, "/api/x")).isTrue();
    assertThat(ApiRole.VIEWER.allows(HttpMethod.OPTIONS, "/api/x")).isTrue();
    assertThat(ApiRole.VIEWER.allows(HttpMethod.POST, "/api/executions/e/priority")).isFalse();
  }

  @Test
  void operator_changesExecutionsAndWorkflowsOnly() {
    assertThat(ApiRole.OPERATOR.allows(HttpMethod.POST, "/api/executions/e/priority")).isTrue();
    assertThat(ApiRole.OPERATOR.allows(HttpMethod.POST, "/api/workflow/start")).isTrue();
    assertThat(ApiRole.OPERATOR.allows(HttpMethod.POST, "/api/sessions")).isFalse();
    assertThat(ApiRole.OPERATOR.allows(HttpMethod.POST, "/api/admin/drain")).isFalse();
    assertThat(ApiRole.OPERATOR.allows(HttpMethod.POST, "/api/executions/../sessions")).isFalse();
  }

  @Test
  void admin_doesEverything() {
    assertThat(ApiRole.ADMIN.allows(HttpMethod.DELETE, "/api/sessions/s")).isTrue();
  }
}
