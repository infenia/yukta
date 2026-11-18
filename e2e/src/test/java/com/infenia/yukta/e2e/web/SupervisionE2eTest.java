// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code /api/supervision}: who owns what, and what the caller may command. */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class SupervisionE2eTest {

  @Test
  void theSummaryCountsWhatNobodyOwns(final Api api) {
    final JsonNode summary =
        api.data(api.client().get().uri("/api/supervision/summary").exchange());

    assertThat(summary.path("unowned").isNumber()).isTrue();
    assertThat(summary.path("unownedWorkflows").isArray()).isTrue();
  }

  @Test
  void ownershipNeedsSessionAndWorkflow(final Api api) {
    api.failure(api.client().get().uri("/api/supervision/ownership").exchange(), 400);
  }

  @Test
  void ownershipOfAnUnknownWorkflowIs404(final Api api) {
    api.failure(
        api.client().get().uri("/api/supervision/ownership?session=e2e-nope&workflow=w").exchange(),
        404);
  }

  @Test
  void workflowNobodySupervisesIsOwnedByTheDefault(final Api api, final Scenarios scenarios) {
    final String session = Scenarios.unique("own");
    scenarios.applySession(session(session));

    final JsonNode owner =
        api.data(
            api.client()
                .get()
                .uri("/api/supervision/ownership?session={s}&workflow=w", session)
                .exchange());

    assertThat(owner.isObject()).isTrue();
  }

  @Test
  void whatTheCallerMayDoIsListedPerCommand(final Api api) {
    final JsonNode all = api.data(api.client().get().uri("/api/supervision").exchange());

    assertThat(all.path("drain").path("allowed").asBoolean()).isTrue();
    assertThat(all.path("setLimits").has("via")).isTrue();
  }

  /** A session with one workflow {@code w}: a trigger and a terminal. */
  /* default */ static String session(final String id) {
    return "{\"sessionId\":\""
        + id
        + "\",\"name\":\"n\",\"description\":\"d\","
        + "\"initiator\":\"e2e\",\"projectPath\":\"/tmp\",\"workflows\":{\"w\":"
        + "{\"workflowId\":\"w\",\"description\":\"d\",\"nodes\":["
        + "{\"nodeId\":\"t\",\"type\":\"CONSTANT_SOURCE\",\"config\":{}},"
        + "{\"nodeId\":\"e\",\"type\":\"CONSOLE_TERMINAL\",\"config\":{}}],"
        + "\"edges\":[{\"source\":\"t\",\"target\":\"e\"}]}}}";
  }
}
