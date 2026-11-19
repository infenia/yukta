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
    assertThat(ApiRole.VIEWER.allows(HttpMethod.POST, "/api/executions/e/pause")).isFalse();
    assertThat(ApiRole.VIEWER.allows(HttpMethod.POST, "/api/executions/e/nodes/n/skip")).isFalse();
    assertThat(ApiRole.VIEWER.allows(HttpMethod.GET, "/api/executions/e/tree")).isTrue();
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
  void readingTheEffectiveConfigurationNeedsAdmin() {
    for (final String path : new String[] {"/api/config", "/api/config/x"}) {
      assertThat(ApiRole.VIEWER.allows(HttpMethod.GET, path)).isFalse();
      assertThat(ApiRole.OPERATOR.allows(HttpMethod.GET, path)).isFalse();
      assertThat(ApiRole.ADMIN.allows(HttpMethod.GET, path)).isTrue();
    }
    // only the config path itself: a look-alike prefix is an ordinary read
    assertThat(ApiRole.VIEWER.allows(HttpMethod.GET, "/api/configs")).isTrue();
  }

  @Test
  void admin_doesEverything() {
    assertThat(ApiRole.ADMIN.allows(HttpMethod.DELETE, "/api/sessions/s")).isTrue();
  }

  @Test
  void deletingSessionsAndWorkflowsNeedsAdmin() {
    for (final String path : new String[] {"/api/sessions/s1", "/api/sessions/s1/workflows/w1"}) {
      assertThat(ApiRole.VIEWER.allows(HttpMethod.DELETE, path)).isFalse();
      assertThat(ApiRole.OPERATOR.allows(HttpMethod.DELETE, path)).isFalse();
      assertThat(ApiRole.ADMIN.allows(HttpMethod.DELETE, path)).isTrue();
    }
  }

  @Test
  void everyRoleChangesItsOwnInboxButNotOtherPaths() {
    for (final ApiRole role : ApiRole.values()) {
      assertThat(role.allows(HttpMethod.POST, "/api/notifications/read")).isTrue();
      assertThat(role.allows(HttpMethod.PUT, "/api/notifications/preferences")).isTrue();
    }
    assertThat(ApiRole.VIEWER.allows(HttpMethod.POST, "/api/notifications/../sessions")).isFalse();
    assertThat(ApiRole.VIEWER.allows(HttpMethod.POST, "/api/notificationsx")).isFalse();
  }

  @Test
  void operatorMayCommandConversationsAndViewerMayNot() {
    assertThat(ApiRole.OPERATOR.allows(HttpMethod.POST, "/api/conversations/c/messages")).isTrue();
    assertThat(ApiRole.OPERATOR.allows(HttpMethod.POST, "/api/conversations/c/end")).isTrue();
    assertThat(ApiRole.VIEWER.allows(HttpMethod.POST, "/api/conversations/c/messages")).isFalse();
  }
}
