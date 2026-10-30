// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.control.ControlHeartbeat;
import com.infenia.yukta.model.control.ControlStatistics;
import com.infenia.yukta.service.control.directive.ControlHeartbeatHandler;
import com.infenia.yukta.service.control.directive.ControlSignalHandler;
import com.infenia.yukta.service.control.directive.ControlStatisticsHandler;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.testsupport.Timing;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Two runs of the same workflow report on the same node ids; their signals must not mix. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ControlBusServiceRunScopeTest {

  private static final String WORKFLOW_ID = "wf";
  private static final String NODE_ID = "node";

  private ControlBusService service;

  @BeforeEach
  void setUp() {
    final List<ControlSignalHandler> handlers =
        List.of(new ControlHeartbeatHandler(), new ControlStatisticsHandler());
    service =
        new ControlBusService(
            100,
            10,
            256,
            handlers,
            mock(PreparedWorkflowCache.class),
            mock(WorkflowOrchestrator.class),
            new ActivePluginRegistry(handlers));
    service.init();
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  private static Message<?> heartbeat(final String executionId) {
    final Message<?> message =
        DefaultMessage.create(UUID.randomUUID(), new ControlHeartbeat(NODE_ID, 1))
            .withWorkflowId(WORKFLOW_ID)
            .withSourceNodeId(NODE_ID);
    return executionId == null ? message : message.withMetadata(Map.of("executionId", executionId));
  }

  private void emitAndWaitFor(final Message<?> message, final String executionId) {
    StepVerifier.create(service.emit(message)).verifyComplete();
    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> service.getLastHeartbeat(WORKFLOW_ID, executionId, NODE_ID) != null);
  }

  @Test
  void heartbeatsOfTwoRunsOfTheSameNodeAreKeptApart() throws InterruptedException {
    final Message<?> first = heartbeat("run-1");
    emitAndWaitFor(first, "run-1");
    Timing.nextMillisecond();
    final Message<?> second = heartbeat("run-2");
    emitAndWaitFor(second, "run-2");

    assertThat(service.getLastHeartbeat(WORKFLOW_ID, "run-1", NODE_ID)).isSameAs(first);
    assertThat(service.getLastHeartbeat(WORKFLOW_ID, "run-2", NODE_ID)).isSameAs(second);
    assertThat(service.getLastHeartbeat(WORKFLOW_ID, NODE_ID))
        .as("without a run: the newest of them")
        .isSameAs(second);
  }

  @Test
  void heartbeatWithoutRunStillWorksAsBefore() {
    final Message<?> message = heartbeat(null);
    StepVerifier.create(service.emit(message)).verifyComplete();

    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> service.getLastHeartbeat(WORKFLOW_ID, NODE_ID) != null);

    assertThat(service.getLastHeartbeat(WORKFLOW_ID, NODE_ID)).isSameAs(message);
  }

  @Test
  void statisticsOfRunAreReadByRunAndNewestAcrossRuns() {
    final Message<?> stats =
        DefaultMessage.create(UUID.randomUUID(), new ControlStatistics(NODE_ID, 1.0, 2.0, Map.of()))
            .withWorkflowId(WORKFLOW_ID)
            .withSourceNodeId(NODE_ID)
            .withMetadata(Map.of("executionId", "run-7"));
    StepVerifier.create(service.emit(stats)).verifyComplete();

    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> service.getLastStatistics(WORKFLOW_ID, "run-7", NODE_ID) != null);

    assertThat(service.getLastStatistics(WORKFLOW_ID, "run-7", NODE_ID)).isSameAs(stats);
    assertThat(service.getLastStatistics(WORKFLOW_ID, "other-run", NODE_ID)).isNull();
    assertThat(service.getLastStatistics(WORKFLOW_ID, NODE_ID)).isSameAs(stats);
  }

  @Test
  void activeNodesListEachNodeOnceWithoutInternalKeys() {
    emitAndWaitFor(heartbeat("run-1"), "run-1");
    emitAndWaitFor(heartbeat("run-2"), "run-2");

    assertThat(service.getActiveNodes(WORKFLOW_ID)).containsExactly(NODE_ID);
    assertThat(service.getActiveNodes()).containsExactly(NODE_ID);
  }
}
