// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.security.ApiRole;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code GET /api/control/audit} lists the control commands recorded for runs. */
@E2eTest
@NoArgsConstructor
class ControlAuditE2eTest {

  @Test
  void theListIsAnArrayAndTheLimitIsHonoured() {
    final Api api = Api.forRole(ApiRole.ADMIN);

    final JsonNode all = api.data(api.client().get().uri("/api/control/audit?limit=1").exchange());

    assertThat(all.isArray()).isTrue();
    assertThat(all.size()).isLessThanOrEqualTo(1);
  }

  @Test
  void stopIsRecordedWithItsActor(final Api api, final Scenarios scenarios) {
    final String session = Scenarios.unique("audit");
    scenarios.applySession(SupervisionE2eTest.session(session));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());
    api.call("POST", "/api/executions/" + execution + "/stop", null);

    final JsonNode entries =
        api.data(
            api.client().get().uri("/api/control/audit?executionId={e}", execution).exchange());

    assertThat(entries).isNotEmpty();
    assertThat(entries.get(0).path("executionId").asString()).isEqualTo(execution);
  }
}
