// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.Workflows;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A run from start to its terminal state, read through every endpoint a user or the UI reads it
 * with.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ExecutionLifecycleE2eTest {

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();
  private static final Duration WAIT = Duration.ofSeconds(30);

  private final Api admin = Api.forRole(com.infenia.yukta.security.ApiRole.ADMIN);

  /** Applies an echo session, starts it and waits for the end; returns {session, execution}. */
  private String[] run(final Scenarios scenarios, final String text) {
    final String session = Scenarios.unique("run");
    scenarios.applySession(Workflows.echo(session, text));
    final String execution = scenarios.startWorkflow(session, "w", Map.of("n", 1));
    assertThat(scenarios.awaitTerminal(execution, WAIT).path("status").asString())
        .isEqualTo("SUCCESS");
    return new String[] {session, execution};
  }

  @Test
  void everyNodeSucceedsAndTheRunIsInTheLists(final Scenarios scenarios) {
    final String[] ids = run(scenarios, "hello-e2e");

    final JsonNode progress =
        admin.data(admin.client().get().uri("/api/executions/{e}", ids[1]).exchange());
    for (final JsonNode task : progress.path("tasks")) {
      assertThat(task.path("status").asString()).isEqualTo("SUCCESS");
    }
    assertThat(progress.path("sessionId").asString()).isEqualTo(ids[0]);
    assertThat(
            admin.call("GET", "/api/sessions/" + ids[0] + "/workflows/w/executions", null).body())
        .contains(ids[1]);
    assertThat(admin.call("GET", "/api/workflow/" + ids[0] + "/history", null).body())
        .contains(ids[1]);
    assertThat(admin.call("GET", "/api/executions?sessionId=" + ids[0], null).body())
        .contains(ids[1]);
    assertThat(admin.call("GET", "/api/executions/" + ids[1] + "/tree", null).status())
        .isEqualTo(200);
    assertThat(
            admin
                .data(admin.client().get().uri("/api/executions/counts").exchange())
                .has("running"))
        .isTrue();
  }

  @Test
  void theStatusStreamEndsWithTheTerminalState(final Scenarios scenarios) {
    final String session = Scenarios.unique("run");
    scenarios.applySession(Workflows.echo(session, "stream"));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());

    final String events =
        Api.eventsUntilEnd(
            admin.client().get().uri("/api/executions/{e}/status/stream", execution), WAIT);

    assertThat(events).contains("SUCCESS");
  }

  @Test
  void theResultsAreReadAsJsonAndAsStreamThatEnds(final Scenarios scenarios) {
    final String[] ids = run(scenarios, "results");
    final String path = "/api/sessions/" + ids[0] + "/executions/" + ids[1] + "/results";

    final JsonNode results = JSON.readTree(admin.call("GET", path, null).body());
    assertThat(results.path("complete").asBoolean()).isTrue();
    assertThat(results.path("status").asString()).isEqualTo("SUCCESS");
    assertThat(results.path("events")).isNotEmpty();

    assertThat(Api.eventsUntilEnd(admin.client().get().uri(path), WAIT)).contains("EXECUTION_END");
  }

  @Test
  void theLogsAndTheNodeOutputHoldWhatTheStepPrinted(final Scenarios scenarios) {
    final String[] ids = run(scenarios, "printed-by-e2e");
    final String logs = "/api/sessions/" + ids[0] + "/executions/" + ids[1] + "/logs";

    assertThat(Api.eventsUntilEnd(admin.client().get().uri(logs), WAIT)).contains("printed-by-e2e");
    assertThat(Api.eventsUntilEnd(admin.client().get().uri(logs + "/entries"), WAIT))
        .contains("printed-by-e2e", "\"pluginName\"");
    final JsonNode output =
        admin.data(
            admin.client().get().uri("/api/executions/{e}/nodes/p/output", ids[1]).exchange());
    assertThat(output.path("lines").toString()).contains("printed-by-e2e");
    assertThat(output.path("totalLines").asInt()).isPositive();
  }

  @Test
  void unknownExecutionIs404Everywhere() {
    for (final String path :
        new String[] {
          "/api/executions/e2e-nope",
          "/api/executions/e2e-nope/tree",
          "/api/executions/e2e-nope/nodes/p/output",
          "/api/sessions/e2e-nope/executions/e2e-nope/results"
        }) {
      assertThat(admin.call("GET", path, null).status()).as(path).isEqualTo(404);
    }
  }

  @Test
  void startingAnUnknownWorkflowIs404Not500() {
    assertThat(
            admin
                .call(
                    "POST",
                    "/api/workflow/start",
                    "{\"sessionId\":\"e2e-nope\",\"workflowId\":\"w\"}")
                .status())
        .isEqualTo(404);
  }
}
