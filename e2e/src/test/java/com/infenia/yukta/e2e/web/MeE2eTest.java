// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.InJvmOnly;
import com.infenia.yukta.security.ApiRole;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code GET /api/me} describes the caller; the UI reads these fields to show or hide actions. */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class MeE2eTest {

  @Test
  void anAdminIsDescribedWithItsPermissions() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    final JsonNode me = api.data(api.client().get().uri("/api/me").exchange());

    assertThat(me.path("authenticated").asBoolean()).isTrue();
    assertThat(me.path("authEnabled").asBoolean()).isTrue();
    assertThat(me.path("role").asString()).isEqualTo("ADMIN");
    assertThat(me.path("keyName").asString()).isNotBlank();
    assertThat(me.path("permissions").path("startRuns").asBoolean()).isTrue();
    assertThat(me.path("permissions").path("editSessions").asBoolean()).isTrue();
  }

  @Test
  @InJvmOnly
  void viewerHasNoWritePermissions() {
    final Api api = Api.forRole(ApiRole.VIEWER);

    final JsonNode me = api.data(api.client().get().uri("/api/me").exchange());

    assertThat(me.path("role").asString()).isEqualTo("VIEWER");
    assertThat(me.path("permissions").path("startRuns").asBoolean()).isFalse();
    assertThat(me.path("permissions").path("editSessions").asBoolean()).isFalse();
    assertThat(me.path("permissions").path("changeLimits").asBoolean()).isFalse();
  }
}
