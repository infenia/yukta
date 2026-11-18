// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The guard presets of the rules engine (failure guard, approval gate, stall watchdog) at work. */
@E2eTest
@NoArgsConstructor
@ExpectedErrorLog(
    sometimes = {
      "ERROR com.infenia.yukta.plugin.process.ProcessExecutorPlugin - ",
      "ERROR com.infenia.yukta.service.control.gateway.DefaultControlBusGateway - ",
      "ERROR com.infenia.yukta.service.control.gateway.RestartCoordinator - ",
      "ERROR com.infenia.yukta.service.control.gateway.WorkflowStarter - ",
      "ERROR com.infenia.yukta.service.orchestrator.WorkflowOrchestrator - ",
      "ERROR com.infenia.yukta.service.orchestrator.strategy.StreamAssemblyHelper - ",
      "ERROR com.infenia.yukta.service.orchestrator.stream.NodeStreams - ",
      "ERROR com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator - "
    })
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.UseConcurrentHashMap"
})
class PresetsE2eTest {

  private static final Duration WAIT = Duration.ofSeconds(30);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String WORKFLOW = "guarded";

  private static Map<String, Object> node(
      final String id, final String type, final Map<String, Object> config) {
    return Map.of("nodeId", id, "type", type, "config", config);
  }

  private static Map<String, Object> shell(final String id, final String script) {
    return node(
        id,
        "PROCESS_EXECUTOR",
        Map.of("command", List.of("sh", "-c", script), "outputFormat", "RAW"));
  }

  /** A trigger feeding a node that fails at once (or sleeps) and a node that sleeps. */
  private static String session(final String id, final boolean failing) {
    final Map<String, Object> workflow =
        Map.of(
            "workflowId",
            WORKFLOW,
            "description",
            "guarded",
            "nodes",
            List.of(
                node("t", "CONSTANT_SOURCE", Map.of()),
                shell("bad", failing ? "exit 3" : "sleep 30"),
                shell("slow", "sleep 30"),
                node("end", "CONSOLE_TERMINAL", Map.of())),
            "edges",
            List.of(
                Map.of("source", "t", "target", "bad"),
                Map.of("source", "t", "target", "slow"),
                Map.of("source", "bad", "target", "end"),
                Map.of("source", "slow", "target", "end")));
    return JSON.writeValueAsString(
        Map.of(
            "sessionId",
            id,
            "name",
            "presets",
            "description",
            "d",
            "initiator",
            "e2e",
            "projectPath",
            "/tmp",
            "workflows",
            Map.of(WORKFLOW, workflow)));
  }

  /** A rule document holding one rule. */
  private static Map<String, Object> document(final Map<String, Object> rule) {
    return Map.of("schemaVersion", 1, "rules", List.of(rule));
  }

  /**
   * A rules supervisor with a guard's rule document (and extra common settings) owning a session.
   */
  private static void attach(
      final Api api,
      final Scenarios scenarios,
      final String id,
      final Map<String, Object> rules,
      final Map<String, Object> common,
      final String session) {
    final Map<String, Object> settings = new LinkedHashMap<>(rules);
    settings.putAll(common);
    // an owner refuses what people send unless it lists the command; the tests start and stop runs
    final List<Object> access =
        new ArrayList<>(List.of("StartWorkflowCommand", "StopWorkflowCommand"));
    access.addAll((List<?>) common.getOrDefault("operatorAccess", List.of()));
    settings.put("operatorAccess", access);
    scenarios.createSupervisor(
        JSON.writeValueAsString(Map.of("id", id, "type", "rules", "settings", settings)));
    final Reply assigned =
        api.call(
            "PUT",
            "/api/supervisors/" + id + "/assignments",
            "[{\"target\":\"session:" + session + "\"}]");
    assertThat(assigned.status()).as(assigned.body()).isEqualTo(200);
  }

  private static void stop(final Api api, final String session) {
    api.call("POST", "/api/workflow/" + session + "/" + WORKFLOW + "/stop?mode=CANCEL", null);
  }

  @Test
  void failureGuardPausesTheWorkflowAfterTheFirstFailure(final Api api, final Scenarios scenarios) {
    final String session = Scenarios.unique("guard");
    final String id = Scenarios.unique("fg");
    attach(
        api,
        scenarios,
        id,
        document(
            Map.of(
                "id",
                "failure-guard",
                "when",
                Map.of(
                    "event",
                    "NodeStatusChanged",
                    "status",
                    "FAILURE",
                    "count",
                    1,
                    "withinSeconds",
                    60,
                    "if",
                    "nodeId != null and (nodeId == 'bad')"),
                "then",
                Map.of("command", "PauseWorkflow"))),
        Map.of(),
        session);
    scenarios.applySession(session(session, true));

    final String executionId = scenarios.startWorkflow(session, WORKFLOW, Map.of());

    // the guard sees the failure and sends its command, whether or not the run is still alive
    await()
        .atMost(WAIT)
        .untilAsserted(() ->
            assertThat(api.call("GET", "/api/supervisors/decisions?supervisor=" + id, null).body())
                .contains("PauseWorkflowCommand", executionId));
    stop(api, session);
  }

  @Test
  void approvalGateThatOwnsTheSessionHoldsRestartsFromPeople(
      final Api api, final Scenarios scenarios) {
    final String session = Scenarios.unique("gate");
    attach(
        api,
        scenarios,
        Scenarios.unique("ag"),
        document(
            Map.of(
                "id",
                "approval-gate-RestartCommand",
                "requireApproval",
                Map.of("command", "RestartCommand", "ttl", 60))),
        Map.of("operatorAccess", List.of("RestartCommand")),
        session);
    scenarios.applySession(session(session, false));
    final String executionId = scenarios.startWorkflow(session, WORKFLOW, Map.of());

    // the owner lets people send a restart, and asks for approval before it runs
    final Reply restart = api.call("POST", "/api/executions/" + executionId + "/restart", null);
    assertThat(restart.status()).as(restart.body()).isEqualTo(202);
    assertThat(restart.body()).contains("requestId");
    // a stop is never parked for a person
    stop(api, session);
  }

  @Test
  void stallWatchdogPausesWorkflowThatDoesNothingForTooLong(
      final Api api, final Scenarios scenarios) {
    final String session = Scenarios.unique("stall");
    final String id = Scenarios.unique("sw");
    // the watchdog is a timed rule over whole runs, so it acts on its own: it needs initiative
    attach(
        api,
        scenarios,
        id,
        document(
            Map.of(
                "id",
                "stall-watchdog",
                "when",
                Map.of("every", "1s", "status", "RUNNING", "if", "idleSeconds > 1"),
                "then",
                Map.of("command", "PauseWorkflow", "reason", "no progress for 1 second"))),
        Map.of("initiative", List.of("PauseWorkflowCommand")),
        session);
    scenarios.applySession(session(session, false));

    final String executionId = scenarios.startWorkflow(session, WORKFLOW, Map.of());

    await()
        .atMost(WAIT)
        .untilAsserted(() ->
            assertThat(
                    api.data(api.client().get().uri("/api/executions/{id}", executionId).exchange())
                        .path("controlState")
                        .asString())
                .as(api.call("GET", "/api/supervisors/decisions?supervisor=" + id, null).body())
                .isEqualTo("PAUSED"));
    stop(api, session);
  }
}
