// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.YuktaServer;
import com.infenia.yukta.security.ApiRole;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/agent/v1}: what an agent supervisor reads and sends, authenticated by its credential
 * and never by a human API key.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestShouldIncludeAssert"})
class AgentApiE2eTest {

  private static JsonNode data(final Api agent, final String path) {
    return agent.data(agent.client().get().uri(path).exchange());
  }

  @Test
  void theAgentIdentifiesItself(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);

    final JsonNode self = data(fixture.agent(), "/api/agent/v1/self");

    assertThat(self.path("supervisor").asString()).isEqualTo(fixture.supervisor());
    assertThat(self.path("contract").asInt()).isPositive();
    assertThat(self.has("connected")).isTrue();
  }

  @Test
  void humanKeyIsNotAnAgentCredential() {
    final Reply reply =
        Api.reply(
            Api.toServer(YuktaServer.baseUrl(), null)
                .client()
                .get()
                .uri("/api/agent/v1/self")
                .header("Authorization", "Bearer " + YuktaServer.key(ApiRole.ADMIN))
                .exchange());

    assertThat(reply.status()).isEqualTo(401);
  }

  @Test
  void theReadEndpointsAnswerWithinTheSupervisedScope(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);
    final Api agent = fixture.agent();

    assertThat(data(agent, "/api/agent/v1/signals").path("signals").isArray()).isTrue();
    assertThat(data(agent, "/api/agent/v1/snapshot").has("cursor")).isTrue();
    assertThat(data(agent, "/api/agent/v1/executions").isArray()).isTrue();
    assertThat(data(agent, "/api/agent/v1/inputs").isArray()).isTrue();
  }

  @Test
  void anUnknownExecutionOrWorkflowIs404(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);
    final Api agent = fixture.agent();

    agent.failure(agent.client().get().uri("/api/agent/v1/executions/e2e-nope").exchange(), 404);
    agent.failure(
        agent.client().get().uri("/api/agent/v1/executions/e2e-nope/logs").exchange(), 404);
    agent.failure(agent.client().get().uri("/api/agent/v1/workflows/e2e-nope/w").exchange(), 404);
  }

  @Test
  void theSchedulerIsOnlyReadableToTheSupervisorThatOwnsTheServer(
      final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);
    final Api agent = fixture.agent();

    agent.failure(agent.client().get().uri("/api/agent/v1/scheduler").exchange(), 404);
  }

  @Test
  void directiveNeedsKeyAndCommand(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);
    final Api agent = fixture.agent();

    final Reply reply = agent.call("POST", "/api/agent/v1/directives", "{}");

    assertThat(reply.status()).as(reply.body()).isEqualTo(400);
    assertThat(agent.call("POST", "/api/agent/v1/directives", "{\"command\":\"nope\"}").status())
        .isEqualTo(400);
  }

  @Test
  void reviewVerdictMustBeOneOfTheKnownOnes(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);

    final Reply reply = fixture.agent().call("POST", "/api/agent/v1/reviews/e2e-nope", "{}");

    assertThat(reply.status()).as(reply.body()).isEqualTo(400);
    assertThat(reply.body()).contains("verdict");
  }

  @Test
  void delegationThatIsNotOpenIs409(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);

    final Reply reply = fixture.agent().call("POST", "/api/agent/v1/delegations/e2e-nope", "{}");

    assertThat(reply.status()).as(reply.body()).isEqualTo(409);
  }

  @Test
  void eventStreamReplaysFromCursor(final Api api, final Scenarios scenarios) {
    final AgentFixture fixture = AgentFixture.create(api, scenarios);

    final String events =
        Api.events(
            fixture.agent().client().get().uri("/api/agent/v1/events?after=0"),
            Duration.ofSeconds(2));

    assertThat(events).contains("STARTED");
  }
}
