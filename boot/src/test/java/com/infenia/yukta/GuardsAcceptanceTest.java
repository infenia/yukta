// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The failure guard and the approval gate, created and attached through the API, at work. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "yukta.process.allow-any=true")
@NoArgsConstructor
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.UnitTestShouldIncludeAssert"})
class GuardsAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  @LocalServerPort private int port;

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;

  private HttpResponse<String> call(final String method, final String path, final String body)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private void post(final String path, final String body) throws Exception {
    final HttpResponse<String> response = call("POST", path, body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }

  /** A trigger feeding a node that fails at once and a node that sleeps. */
  private String workflow(final String session, final boolean failing) {
    final String id = "wf-" + UUID.randomUUID();
    definitions
        .save(
            session,
            new WorkflowDefinition(
                id,
                "guarded",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    new Node(
                        "bad",
                        "PROCESS_EXECUTOR",
                        Map.of(
                            "command",
                            List.of("sh", "-c", failing ? "exit 3" : "sleep 60"),
                            "outputFormat",
                            "RAW")),
                    new Node(
                        "slow",
                        "PROCESS_EXECUTOR",
                        Map.of("command", List.of("sleep", "60"), "outputFormat", "RAW")),
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(
                    new Edge("t", "bad", null),
                    new Edge("t", "slow", null),
                    new Edge("bad", "end", null),
                    new Edge("slow", "end", null))))
        .block();
    return id;
  }

  private void attach(
      final String id, final String type, final String settings, final String session)
      throws Exception {
    post(
        "/api/supervisors",
        "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"settings\":" + settings + "}");
    post(
        "/api/supervisors/attachments",
        "{\"id\":\"at-"
            + id
            + "\",\"supervisorId\":\""
            + id
            + "\",\"scope\":\"session:"
            + session
            + "\"}");
  }

  @Test
  void failureGuardPausesTheWorkflowAfterTheFirstFailure() throws Exception {
    final String session = "guard-" + UUID.randomUUID();
    final String id = "fg-" + UUID.randomUUID().toString().substring(0, 8);
    attach(id, "failure-guard", "{\"failures\":1,\"nodes\":[\"bad\"]}", session);
    final String workflowId = workflow(session, true);

    final String executionId = gateway.startWorkflow(session, workflowId).block();

    // the guard sees the failure and sends its command, whether or not the run is still alive
    await()
        .atMost(WAIT)
        .untilAsserted(
            () ->
                assertThat(call("GET", "/api/supervisors/decisions?supervisor=" + id, "").body())
                    .contains("PauseWorkflowCommand", executionId));
  }

  @Test
  void approvalGateHoldsTheStopAnotherSupervisorSends() throws Exception {
    final String session = "gate-" + UUID.randomUUID();
    final String tag = UUID.randomUUID().toString().substring(0, 8);
    attach(
        "ag-" + tag,
        "approval-gate",
        "{\"commands\":[\"StopWorkflowCommand\"],\"ttl\":60}",
        session);
    attach(
        "rs-" + tag,
        "rule-supervisor",
        "{\"rules\":[{\"id\":\"stop\",\"when\":{\"event\":\"NodeStatusChanged\","
            + "\"status\":\"RUNNING\",\"module\":\"PROCESS_EXECUTOR\"},"
            + "\"then\":{\"command\":\"StopWorkflow\"}}]}",
        session);
    final String workflowId = workflow(session, false);

    final String executionId = gateway.startWorkflow(session, workflowId).block();

    // the rule supervisor's stop is parked for approval instead of being applied
    await()
        .atMost(WAIT)
        .untilAsserted(
            () ->
                assertThat(
                        call("GET", "/api/supervisors/decisions?supervisor=rs-" + tag, "").body())
                    .contains("StopWorkflowCommand", "awaiting-approval", executionId));
    gateway.stopWorkflow(session, workflowId, "cleanup", StopMode.CANCEL).block();
  }

  @Test
  void stallWatchdogPausesTheWorkflowOfNodeThatRunsTooLong() throws Exception {
    final String session = "stall-" + UUID.randomUUID();
    final String id = "sw-" + UUID.randomUUID().toString().substring(0, 8);
    attach(
        id,
        "stall-watchdog",
        "{\"maxRunningSeconds\":1,\"checkEverySeconds\":1,\"action\":\"PAUSE_WORKFLOW\"}",
        session);
    final String workflowId = workflow(session, false);

    final String executionId = gateway.startWorkflow(session, workflowId).block();

    await()
        .atMost(WAIT)
        .untilAsserted(
            () ->
                assertThat(tracker.getProgressByExecutionId(executionId).controlState())
                    .isEqualTo("PAUSED"));
    gateway.stopWorkflow(session, workflowId, "cleanup", StopMode.CANCEL).block();
  }
}
