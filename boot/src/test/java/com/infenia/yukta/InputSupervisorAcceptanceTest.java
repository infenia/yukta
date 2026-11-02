// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.SupervisorService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.supervisor.rules.RuleSupervisorFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A rule supervisor attached at run time answers the input requests of {@code ASK_INPUT}: a LOW
 * risk request is answered by the rule and the run goes on; the same rule cannot answer a HIGH risk
 * request, which the authoriser denies and counts, so it stays open until a person answers it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@NoArgsConstructor
class InputSupervisorAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  private static final String PROMPT = "Deploy build 42 to staging?";

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;
  @Autowired private SupervisorService supervisors;
  @Autowired private InputBroker broker;
  @Autowired private MeterRegistry meters;

  /** Starts {@code CONSTANT_SOURCE -> ASK_INPUT -> CONSOLE_TERMINAL} and returns the run id. */
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
                    new Node("q", "ASK_INPUT", Map.of("prompt", PROMPT, "risk", risk)),
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(new Edge("t", "q", null), new Edge("q", "end", null))))
        .block(WAIT);
    return Objects.requireNonNull(gateway.startWorkflow(session, workflowId).block(WAIT));
  }

  private InputRequest openRequest(final String executionId) {
    await()
        .atMost(WAIT)
        .until(
            () -> broker.listOpen(null, executionId).hasElements().blockOptional().orElse(false));
    return Objects.requireNonNull(broker.listOpen(null, executionId).blockFirst(WAIT));
  }

  private double deniedForRisk() {
    final Counter counter = meters.find("yukta.input.denied").tag("rule", "risk").counter();
    return counter == null ? 0 : counter.count();
  }

  private TaskStatus status(final String executionId) {
    return tracker.getProgressByExecutionId(executionId).status();
  }

  @Test
  void ruleAnswersLowRiskRequestButIsDeniedOnHighRisk() {
    final String session = "inputs-" + UUID.randomUUID();
    final String profileId = "approve-" + UUID.randomUUID().toString().substring(0, 8);
    supervisors
        .saveProfile(
            new SupervisorProfile(
                profileId,
                RuleSupervisorFactory.PROFILE_TYPE,
                Map.of(
                    "canAnswer",
                    true,
                    "maxRisk",
                    "MEDIUM",
                    "rules",
                    List.of(
                        Map.of(
                            "id", "approve-staging-deploys",
                            "when",
                                Map.of(
                                    "event", "InputRequested", "if", "prompt == '" + PROMPT + "'"),
                            "then",
                                Map.of(
                                    "answer",
                                    Map.of("decision", "approve", "rationale", "policy 7"))))),
                false))
        .block(WAIT);
    supervisors
        .attach(
            new SupervisorBinding(profileId, profileId, new SupervisorScope.Session(session), true))
        .block(WAIT);
    try {
      final String low = ask(session, "LOW");
      await().atMost(WAIT).until(() -> status(low) == TaskStatus.SUCCESS);

      final double deniedBefore = deniedForRisk();
      final String high = ask(session, "HIGH");
      final InputRequest request = openRequest(high);
      await().atMost(WAIT).until(() -> deniedForRisk() > deniedBefore);
      assertThat(broker.find(request.id()).map(InputRequest::state).block(WAIT))
          .isEqualTo(InputState.OPEN);
      assertThat(status(high)).isEqualTo(TaskStatus.RUNNING);

      // a person (no API key configured: the caller is a human) may answer it
      gateway.answerInput(high, request.id(), null, "approve", null, null, null, null).block(WAIT);
      await().atMost(WAIT).until(() -> status(high) == TaskStatus.SUCCESS);
    } finally {
      supervisors.detach(profileId).block(WAIT);
    }
  }
}
