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

/** {@code GET /api/edition} names the edition and its license. */
@E2eTest
@NoArgsConstructor
class EditionE2eTest {

  @Test
  void theCommunityEditionIsReported() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    final JsonNode edition = api.data(api.client().get().uri("/api/edition").exchange());

    assertThat(edition.path("edition").asString()).isEqualTo("COMMUNITY");
    assertThat(edition.path("license").asString()).isEqualTo("Apache-2.0");
    assertThat(edition.path("features").isArray()).isTrue();
  }

  @Test
  @InJvmOnly
  void viewerMayRead() {
    final Api api = Api.forRole(ApiRole.VIEWER);

    assertThat(api.data(api.client().get().uri("/api/edition").exchange()).has("edition")).isTrue();
  }
}
