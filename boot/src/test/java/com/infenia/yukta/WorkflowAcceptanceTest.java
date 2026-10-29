// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Drives the real application (real plugins, tracker, scheduler and control bus) through the
 * scenarios the round-2 phases promised, so they cannot silently regress.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@TestPropertySource(properties = "yukta.stop.drain-timeout=2s")
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
@ExpectedErrorLog({
  "ERROR com.infenia.yukta.plugin.process.ProcessExecutorPlugin - ",
  "ERROR com.infenia.yukta.service.orchestrator.stream.NodeStreams - ",
  "ERROR com.infenia.yukta.service.orchestrator.strategy.StreamAssemblyHelper - ",
  "ERROR com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator - ",
  "ERROR com.infenia.yukta.service.orchestrator.WorkflowOrchestrator - ",
  "ERROR com.infenia.yukta.service.control.gateway.WorkflowStarter - "
})
class WorkflowAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;
  @Autowired private ExecutionLogStore executionLog;

  private static Node process(final String nodeId, final List<String> command) {
    return new Node(nodeId, "PROCESS_EXECUTOR", Map.of("command", command, "outputFormat", "RAW"));
  }

  /** Defines a trigger, the given step and a terminal, and returns the session and workflow ids. */
  private String define(final Node step) {
    final String session = "acc-" + UUID.randomUUID();
    final String workflowId = "wf-" + UUID.randomUUID();
    definitions
        .save(
            session,
            new WorkflowDefinition(
                workflowId,
                "acceptance",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    step,
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(new Edge("t", step.nodeId(), null), new Edge(step.nodeId(), "end", null))))
        .block();
    return session + "/" + workflowId;
  }

  private String start(final String key) {
    final String[] parts = key.split("/");
    return gateway.startWorkflow(parts[0], parts[1]).block();
  }

  private WorkflowProgress awaitTerminal(final String executionId) {
    return awaitTerminal(executionId, WAIT);
  }

  private WorkflowProgress awaitTerminal(final String executionId, final Duration limit) {
    await()
        .atMost(limit)
        .until(
            () -> {
              final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
              return progress != null && isTerminal(progress.status());
            });
    return tracker.getProgressByExecutionId(executionId);
  }

  private static boolean isTerminal(final String status) {
    return !List.of("PENDING", "QUEUED", "RUNNING").contains(status);
  }

  @Test
  void successfulRun_endsSuccess_withEveryNodeFinished_andIsLogged() {
    final String executionId = start(define(process("p", List.of("echo", "hello"))));

    final WorkflowProgress progress = awaitTerminal(executionId);

    assertThat(progress.status()).isEqualTo("SUCCESS");
    assertThat(progress.tasks()).allSatisfy(task -> assertThat(task.status()).isEqualTo("SUCCESS"));
    assertThat(executionLog.readAll())
        .filteredOn(event -> event.executionId().equals(executionId))
        .extracting(ExecutionLogEvent::type)
        .contains(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
  }

  @Test
  void stoppingRunningWorkflow_leavesNoNodeRunning_andRecordsCommand() {
    final String key = define(process("p", List.of("sleep", "60")));
    final String executionId = start(key);
    await()
        .atMost(WAIT)
        .until(
            () ->
                tracker.getProgressByExecutionId(executionId).tasks().stream()
                    .anyMatch(
                        task -> "p".equals(task.nodeId()) && "RUNNING".equals(task.status())));
    final String[] parts = key.split("/");

    gateway.stopWorkflow(parts[0], parts[1], "test", StopMode.CANCEL).block();

    final WorkflowProgress progress = awaitTerminal(executionId);
    assertThat(progress.status()).isEqualTo("STOPPED");
    assertThat(progress.tasks()).noneMatch(task -> "RUNNING".equals(task.status()));
    assertThat(executionLog.readAll())
        .filteredOn(event -> event.executionId().equals(executionId))
        .extracting(ExecutionLogEvent::type)
        .contains(ExecutionLogEvent.Type.CONTROL_APPLIED);
  }

  @Test
  void flakyStep_isRetried_andRunSucceeds() throws IOException {
    final Path marker = Files.createTempDirectory("flaky").resolve("seen");
    final Node flaky =
        new Node(
            "p",
            "PROCESS_EXECUTOR",
            Map.of(
                "command",
                List.of("sh", "-c", "test -f " + marker + " || { touch " + marker + "; exit 1; }"),
                "engine",
                Map.of("retry", Map.of("maxAttempts", 3, "backoffMs", 50, "maxBackoffMs", 200))));

    final WorkflowProgress progress = awaitTerminal(start(define(flaky)));

    assertThat(progress.status()).isEqualTo("SUCCESS");
    assertThat(marker).exists();
  }

  @Test
  void failingStep_failsRun_andLeavesNoNodeRunning() {
    final Node failing =
        new Node("p", "PROCESS_EXECUTOR", Map.of("command", List.of("sh", "-c", "exit 3")));

    final WorkflowProgress progress = awaitTerminal(start(define(failing)));

    assertThat(progress.status()).isEqualTo("FAILURE");
    assertThat(progress.tasks()).noneMatch(task -> "RUNNING".equals(task.status()));
    assertThat(progress.tasks())
        .filteredOn(task -> "p".equals(task.nodeId()))
        .allMatch(task -> "FAILURE".equals(task.status()));
    // only the node that failed reports FAILURE; the one behind it shows why it did not finish
    assertThat(progress.tasks())
        .filteredOn(task -> "end".equals(task.nodeId()))
        .allMatch(task -> "UPSTREAM_FAILED".equals(task.status()));
  }

  @Test
  void finishedRun_canBeRestarted_andSucceedsAgain() {
    final String executionId = start(define(process("p", List.of("echo", "again"))));
    awaitTerminal(executionId);

    final String restarted = gateway.restartWorkflow(executionId).block();

    assertThat(restarted).isNotEqualTo(executionId);
    assertThat(awaitTerminal(restarted).status()).isEqualTo("SUCCESS");
  }

  @Test
  void stepSilentOverThirtySeconds_isNotCutOffByGenericDefault() {
    final String key = define(process("p", List.of("sleep", "31")));

    final WorkflowProgress progress = awaitTerminal(start(key), Duration.ofSeconds(90));

    assertThat(progress.status()).isEqualTo("SUCCESS");
  }
}
