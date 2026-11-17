// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.Workflows;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Control of a run that is held by a {@code sleep} step: pause, resume and stop, the node controls,
 * retry and the scheduler.
 */
@E2eTest
@NoArgsConstructor
@ExpectedErrorLog(
    sometimes = {
      "ERROR com.infenia.yukta.plugin.process.ProcessExecutorPlugin - ",
      "ERROR com.infenia.yukta.service.control.gateway.DefaultControlBusGateway - ",
      "ERROR com.infenia.yukta.service.control.gateway.RestartCoordinator - ",
      "ERROR com.infenia.yukta.service.control.gateway.WorkflowStarter - ",
      "ERROR com.infenia.yukta.service.control.processor.RestartFromNodeCommandProcessor - ",
      "ERROR com.infenia.yukta.service.orchestrator.WorkflowOrchestrator - ",
      "ERROR com.infenia.yukta.service.orchestrator.strategy.StreamAssemblyHelper - ",
      "ERROR com.infenia.yukta.service.orchestrator.stream.NodeStreams - ",
      "ERROR com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator - "
    })
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class ExecutionControlE2eTest {

  private static final Duration WAIT = Duration.ofSeconds(30);
  private static final String EXECUTIONS = "/api/executions/";

  private final Api admin = Api.forRole(com.infenia.yukta.security.ApiRole.ADMIN);

  private String start(final Scenarios scenarios, final String definition, final String session) {
    scenarios.applySession(definition);
    return scenarios.startWorkflow(session, "w", Map.of());
  }

  private JsonNode progress(final String execution) {
    return admin.data(admin.client().get().uri(EXECUTIONS + "{e}", execution).exchange());
  }

  private void awaitControlState(final String execution, final String state) {
    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> assertThat(progress(execution).path("controlState").asString()).isEqualTo(state));
  }

  private void stop(final String session) {
    admin.call("POST", "/api/workflow/" + session + "/w/stop?mode=CANCEL", null);
  }

  @Test
  void runIsPausedResumedAndStopped(final Scenarios scenarios) {
    final String session = Scenarios.unique("control");
    final String execution = start(scenarios, Workflows.sleeper(session, 30), session);

    assertThat(admin.call("POST", EXECUTIONS + execution + "/pause", null).status()).isEqualTo(200);
    awaitControlState(execution, "PAUSED");
    assertThat(admin.call("POST", EXECUTIONS + execution + "/resume", null).status())
        .isEqualTo(200);
    await()
        .atMost(WAIT)
        .untilAsserted(() ->
            assertThat(progress(execution).path("controlState").asString()).isNotEqualTo("PAUSED"));

    assertThat(admin.call("POST", EXECUTIONS + execution + "/stop?mode=CANCEL", null).status())
        .isEqualTo(200);
    assertThat(scenarios.awaitTerminal(execution, WAIT).path("status").asString())
        .isIn("STOPPED", "CANCELLED");
  }

  @Test
  void nodeIsPausedResumedSkippedSteppedAndStopped(final Scenarios scenarios) {
    final String session = Scenarios.unique("control");
    final String execution = start(scenarios, Workflows.sleeper(session, 30), session);
    final String node = EXECUTIONS + execution + "/nodes/s/";

    for (final String action : new String[] {"pause", "resume", "step/enable", "step/disable"}) {
      assertThat(admin.call("POST", node + action, null).status()).as(action).isEqualTo(200);
    }
    assertThat(admin.call("POST", node + "skip?skip=true", null).status()).isEqualTo(200);
    assertThat(admin.call("POST", node + "stop", null).status()).isEqualTo(200);
    stop(session);
  }

  @Test
  void breakpointIsSetAndCleared(final Scenarios scenarios) {
    final String session = Scenarios.unique("control");
    final String execution = start(scenarios, Workflows.sleeper(session, 30), session);
    final String breakpoint = EXECUTIONS + execution + "/nodes/e/breakpoint";

    assertThat(admin.call("POST", breakpoint + "?field=payload.status&equals=error", null).status())
        .isEqualTo(200);
    assertThat(admin.call("DELETE", breakpoint, null).status()).isEqualTo(200);
    assertThat(admin.call("POST", breakpoint, null).status())
        .as("field and equals are required")
        .isEqualTo(400);
    stop(session);
  }

  @Test
  void onlyFailedNodeCanBeRetried(final Scenarios scenarios) {
    final String running = Scenarios.unique("control");
    final String runningExecution = start(scenarios, Workflows.sleeper(running, 30), running);
    assertThat(admin.call("POST", EXECUTIONS + runningExecution + "/nodes/s/retry", null).status())
        .isEqualTo(400);
    stop(running);

    final String failing = Scenarios.unique("control");
    final String failedExecution = start(scenarios, Workflows.failing(failing), failing);
    assertThat(scenarios.awaitTerminal(failedExecution, WAIT).path("status").asString())
        .isIn("FAILURE", "ERROR");
    final Reply retry = admin.call("POST", EXECUTIONS + failedExecution + "/nodes/f/retry", null);
    assertThat(retry.status()).as(retry.body()).isEqualTo(200);
    assertThat(retry.body()).contains("executionId");
  }

  @Test
  void stoppedWaitingRunEnds(final Scenarios scenarios) {
    final String session = Scenarios.unique("control");
    final String execution = start(scenarios, Workflows.asking(session), session);
    await()
        .atMost(WAIT)
        .untilAsserted(() ->
            assertThat(admin.call("GET", "/api/sessions/" + session + "/inputs", null).body())
                .contains(execution));

    stop(session);

    assertThat(scenarios.awaitTerminal(execution, WAIT).path("status").asString())
        .isIn("STOPPED", "CANCELLED");
  }

  @Test
  void priorityChangeNeedsQueuedRun(final Scenarios scenarios) {
    final String session = Scenarios.unique("control");
    final String execution = start(scenarios, Workflows.sleeper(session, 30), session);

    assertThat(admin.call("POST", EXECUTIONS + execution + "/priority?priority=5", null).status())
        .isBetween(400, 409);
    stop(session);
  }

  @Test
  void unknownExecutionIs404(final Api api) {
    assertThat(api.call("POST", EXECUTIONS + "e2e-nope/pause", null).status()).isEqualTo(404);
    assertThat(api.call("POST", EXECUTIONS + "e2e-nope/stop", null).status()).isEqualTo(404);
  }

  @Test
  void theSchedulerLimitsAndDrainStateAreReadable(final Api api) {
    final JsonNode limits = api.data(api.client().get().uri("/api/scheduler/limits").exchange());
    final JsonNode drain = api.data(api.client().get().uri("/api/admin/drain").exchange());

    assertThat(limits.has("maxConcurrent")).isTrue();
    assertThat(limits.has("queueCapacity")).isTrue();
    assertThat(drain.path("draining").asBoolean()).isFalse();
  }
}
