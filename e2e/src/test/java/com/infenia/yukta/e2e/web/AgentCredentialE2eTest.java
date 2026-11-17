// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/supervisors/{id}/credential}: issue, describe and revoke the credential of an {@code
 * http} supervisor.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class AgentCredentialE2eTest {

  private static final String CREDENTIAL = "/api/supervisors/{id}/credential";

  @Test
  void credentialIsIssuedDescribedAndRevoked(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("cred");
    scenarios.createSupervisor("{\"id\":\"" + id + "\",\"type\":\"http\",\"settings\":{}}");

    final JsonNode issued = api.data(api.client().post().uri(CREDENTIAL, id).exchange());
    assertThat(issued.path("token").asString()).startsWith("yag_");
    assertThat(issued.has("expiresAt")).isTrue();

    final JsonNode described = api.data(api.client().get().uri(CREDENTIAL, id).exchange());
    assertThat(described.has("issuedAt")).isTrue();
    assertThat(described.has("lastUsedAt")).isTrue();
    assertThat(described.toString()).doesNotContain(issued.path("token").asString());

    assertThat(api.call("DELETE", "/api/supervisors/" + id + "/credential", null).status())
        .isIn(200, 204);
    api.failure(api.client().get().uri(CREDENTIAL, id).exchange(), 404);
  }

  @Test
  void onlyHttpSupervisorHasCredential(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("cred");
    scenarios.createSupervisor(
        "{\"id\":\"" + id + "\",\"type\":\"rules\",\"settings\":{\"rules\":[]}}");

    api.failure(api.client().post().uri(CREDENTIAL, id).exchange(), 409);
  }

  @Test
  void anUnknownSupervisorIs404(final Api api) {
    final Reply reply = api.call("POST", "/api/supervisors/e2e-nope/credential", null);

    assertThat(reply.status()).as(reply.body()).isEqualTo(404);
  }
}
