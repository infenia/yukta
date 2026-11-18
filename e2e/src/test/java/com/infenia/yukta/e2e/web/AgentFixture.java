// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.YuktaServer;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** An {@code http} supervisor that owns a session, with a credential and a client that sends it. */
record AgentFixture(String supervisor, String session, String token, Api agent) {

  /**
   * Creates the supervisor (removed after the test), owns a fresh session with it and issues the
   * credential.
   */
  /* default */ static AgentFixture create(final Api admin, final Scenarios scenarios) {
    final String supervisor = Scenarios.unique("agent");
    final String session = Scenarios.unique("agentsession");
    scenarios.createSupervisor(
        "{\"id\":\""
            + supervisor
            + "\",\"type\":\"http\",\"settings\":{\"operatorAccess\":[\"StartWorkflowCommand\"]}}");
    assertThat(
            admin
                .call(
                    "PUT",
                    "/api/supervisors/" + supervisor + "/assignments",
                    "[{\"target\":\"session:" + session + "\"}]")
                .status())
        .isEqualTo(200);
    final JsonNode issued =
        admin.data(
            admin.client().post().uri("/api/supervisors/{id}/credential", supervisor).exchange());
    final String token = issued.path("token").asString();
    assertThat(token).startsWith("yag_");
    final Api agent = Api.bearer(YuktaServer.baseUrl(), token);
    // the engine starts a moment after the assignment; until then the agent API answers 404
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> agent.call("GET", "/api/agent/v1/signals", null).status() == 200);
    return new AgentFixture(supervisor, session, token, agent);
  }
}
