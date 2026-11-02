// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** A branch behind a routed edge that is not taken ends SKIPPED, and the run still succeeds. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@NoArgsConstructor
class SkippedBranchAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;

  private static Node shell(final String nodeId, final String script) {
    return new Node(
        nodeId,
        "PROCESS_EXECUTOR",
        Map.of(
            "command",
            List.of("sh", "-c", script),
            "outputFormat",
            "RAW",
            "failureMode",
            "CONTINUE",
            "routeByExitCode",
            true));
  }

  @Test
  void untakenBranch_endsSkipped_andTheRunSucceeds() {
    final String session = "skip-" + UUID.randomUUID();
    final String workflowId = "wf-" + UUID.randomUUID();
    definitions
        .save(
            session,
            new WorkflowDefinition(
                workflowId,
                "routed",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    shell("check", "true"),
                    new Node("ok", "CONSOLE_TERMINAL", Map.of()),
                    shell("repair", "true"),
                    new Node("fixed", "CONSOLE_TERMINAL", Map.of())),
                List.of(
                    new Edge("t", "check", null),
                    new Edge("check", "ok", "success"),
                    new Edge("check", "repair", "failure"),
                    new Edge("repair", "fixed", "success"))))
        .block();

    final String executionId = gateway.startWorkflow(session, workflowId).block();

    await()
        .atMost(WAIT)
        .until(
            () -> {
              final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
              return progress != null && progress.status().isTerminal();
            });
    final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
    final Map<String, TaskStatus> statuses =
        progress.tasks().stream()
            .collect(Collectors.toMap(TaskProgress::nodeId, TaskProgress::status));

    assertThat(progress.status()).isEqualTo(TaskStatus.SUCCESS);
    // the trigger and the taken branch ran; the branch behind the untaken port never did
    assertThat(statuses)
        .containsEntry("t", TaskStatus.SUCCESS)
        .containsEntry("check", TaskStatus.SUCCESS)
        .containsEntry("ok", TaskStatus.SUCCESS)
        .containsEntry("repair", TaskStatus.SKIPPED)
        .containsEntry("fixed", TaskStatus.SKIPPED);
  }
}
