// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.supervisor.SupervisorService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * The workflow in {@code examples/17-supervisor-workflow} supervises: it approves a LOW risk
 * request raised by {@code ASK_INPUT} and the run goes on, and it hands a MEDIUM risk request up
 * with its reason as advice, so the request stays open.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@NoArgsConstructor
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.UseConcurrentHashMap"})
class WorkflowSupervisorAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  private static final Path EXAMPLE = Path.of("../examples/17-supervisor-workflow");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;
  @Autowired private SupervisorService supervisors;
  @Autowired private SessionService sessions;
  @Autowired private InputBroker broker;

  private String ask(final String session, final String risk) {
    final String workflowId = "wf-" + UUID.randomUUID();
    definitions
        .save(
            session,
            new WorkflowDefinition(
                workflowId,
                "asks",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    new Node("q", "ASK_INPUT", Map.of("prompt", "Deploy?", "risk", risk)),
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(new Edge("t", "q", null), new Edge("q", "end", null))))
        .block(WAIT);
    return Objects.requireNonNull(gateway.startWorkflow(session, workflowId).block(WAIT));
  }

  /** The advice on the open request of the run, or {@code null} while there is none. */
  private String advice(final String executionId) {
    final InputRequest open = broker.listOpen(null, executionId).blockFirst(WAIT);
    return open == null ? null : open.advice();
  }

  private TaskStatus status(final String executionId) {
    return tracker.getProgressByExecutionId(executionId).status();
  }

  @Test
  void exampleWorkflowApprovesLowRiskAndHandsUpTheRest() throws IOException {
    final String internal = "sup-" + UUID.randomUUID().toString().substring(0, 8);
    final String user = "user-" + UUID.randomUUID().toString().substring(0, 8);
    final String profileId = "wf-sup-" + UUID.randomUUID().toString().substring(0, 8);

    final SessionConfigData example =
        JSON.readValue(Files.readString(EXAMPLE.resolve("session.json")), SessionConfigData.class);
    sessions
        .applyConfig(
            new SessionConfigData(
                internal,
                example.name(),
                example.description(),
                example.initiator(),
                example.tags(),
                example.projectPath(),
                example.workflows()))
        .block(WAIT);

    final Supervisor exampleProfile =
        JSON.readValue(Files.readString(EXAMPLE.resolve("profile.json")), Supervisor.class);
    final Map<String, Object> settings = new HashMap<>(exampleProfile.settings());
    settings.put("sessionId", internal);
    supervisors
        .saveSupervisor(new Supervisor(profileId, exampleProfile.type(), settings, false))
        .block(WAIT);
    supervisors
        .attach(
            new SupervisorAttachment(profileId, profileId, new SupervisorScope.Session(user), true))
        .block(WAIT);
    try {
      final String low = ask(user, "LOW");
      await().atMost(WAIT).until(() -> status(low) == TaskStatus.SUCCESS);

      final String medium = ask(user, "MEDIUM");
      await().atMost(WAIT).until(() -> advice(medium) != null);
      final InputRequest request =
          Objects.requireNonNull(broker.listOpen(null, medium).blockFirst(WAIT));
      assertThat(request.state()).isNotEqualTo(InputState.ANSWERED);
      assertThat(request.advice()).contains("not low risk");
      assertThat(status(medium)).isEqualTo(TaskStatus.RUNNING);

      gateway
          .answerInput(medium, request.id(), null, "approve", null, null, null, null)
          .block(WAIT);
      await().atMost(WAIT).until(() -> status(medium) == TaskStatus.SUCCESS);
    } finally {
      supervisors.detach(profileId).block(WAIT);
    }
  }
}
