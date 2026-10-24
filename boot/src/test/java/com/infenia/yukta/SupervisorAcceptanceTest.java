// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Attaches the rule supervisor to the real control bus: a rule reacts to a running node by pausing
 * the workflow, and another rule refuses to kill it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
@ExpectedErrorLog("ERROR com.infenia.yukta.service.control.gateway.DefaultControlBusGateway - ")
class SupervisorAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  private static final String RULES_JSON =
      """
      {"rules":[
        {"id":"pause-when-running",
         "when":{"event":"NodeStatusChanged","status":"RUNNING","module":"PROCESS_EXECUTOR"},
         "then":{"command":"PauseWorkflow"}},
        {"id":"no-kill",
         "deny":{"command":"StopWorkflow","if":"mode == 'KILL'","reason":"use DRAIN or CANCEL"}}
      ]}
      """;

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;
  @Autowired private ExecutionLogStore executionLog;

  @DynamicPropertySource
  /* default */ static void rules(final DynamicPropertyRegistry registry) throws IOException {
    final Path file = Files.createTempFile("rules", ".json");
    Files.writeString(file, RULES_JSON);
    registry.add("yukta.supervisor.rules.file", file::toString);
  }

  @Test
  void rule_pausesRunningWorkflow_andDeniesKill() {
    final String session = "sup-" + UUID.randomUUID();
    final String workflowId = "wf-" + UUID.randomUUID();
    final Node step =
        new Node(
            "p",
            "PROCESS_EXECUTOR",
            Map.of("command", List.of("sleep", "60"), "outputFormat", "RAW"));
    definitions
        .save(
            session,
            new WorkflowDefinition(
                workflowId,
                "supervised",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    step,
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(new Edge("t", "p", null), new Edge("p", "end", null))))
        .block();

    final String executionId = gateway.startWorkflow(session, workflowId).block();

    // the rule pauses the run once the node is running
    await()
        .atMost(WAIT)
        .untilAsserted(
            () ->
                assertThat(tracker.getProgressByExecutionId(executionId).controlState())
                    .isEqualTo("PAUSED"));
    // the control log entry is written after the state flips, so wait for it too
    await()
        .atMost(WAIT)
        .untilAsserted(
            () ->
                assertThat(executionLog.readAll())
                    .filteredOn(event -> event.executionId().equals(executionId))
                    .anySatisfy(
                        event -> {
                          assertThat(event.type())
                              .isEqualTo(ExecutionLogEvent.Type.CONTROL_APPLIED);
                          assertThat(event.status()).isEqualTo("PauseWorkflowCommand [by rules]");
                        }));

    // the same rule file refuses a kill, from anyone
    assertThatThrownBy(() -> gateway.stopWorkflow(session, workflowId, "x", StopMode.KILL).block())
        .isInstanceOf(ControlRejectedException.class)
        .hasMessageContaining("use DRAIN or CANCEL");

    gateway.stopWorkflow(session, workflowId, "cleanup", StopMode.CANCEL).block();
  }
}
