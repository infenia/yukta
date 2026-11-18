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

/**
 * The usage and presets of a supervisor engine (the catalog itself is in {@code
 * SupervisorApiE2eTest}).
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class SupervisorTypesE2eTest {

  private final Api api = Api.forRole(ApiRole.ADMIN);

  @Test
  void theCatalogCarriesDisplayFieldsForTheUi() {
    final JsonNode types = api.data(api.client().get().uri("/api/supervisor-engines").exchange());

    for (final JsonNode type : types) {
      assertThat(type.path("type").asString()).isNotBlank();
      assertThat(type.path("displayName").asString()).isNotBlank();
      assertThat(type.path("category").asString()).isNotBlank();
    }
  }

  @Test
  void usageListsTheSupervisorsOfOneType() {
    final JsonNode usage =
        api.data(api.client().get().uri("/api/supervisor-engines/rules/usage").exchange());

    assertThat(usage.isArray()).isTrue();
  }

  @Test
  void presetsHaveNameDescriptionAndSettings() {
    final JsonNode presets =
        api.data(api.client().get().uri("/api/supervisor-engines/rules/presets").exchange());

    assertThat(presets).isNotEmpty();
    for (final JsonNode preset : presets) {
      assertThat(preset.path("name").asString()).isNotBlank();
      assertThat(preset.path("description").asString()).isNotBlank();
      assertThat(preset.path("settings").isObject()).isTrue();
    }
  }

  @Test
  void anUnknownTypeIs404ForUsageAndPresets() {
    api.failure(api.client().get().uri("/api/supervisor-engines/nope/usage").exchange(), 404);
    api.failure(api.client().get().uri("/api/supervisor-engines/nope/presets").exchange(), 404);
  }
}
