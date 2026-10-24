// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A stage with several exits: a check that fails, a repair that fixes it, and an {@code
 * OUTCOME_TERMINAL} that asks for a retry. A rule restarts the run from the check on that outcome
 * and only while the attempt budget lasts; an unrepairable stage is not restarted.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({"PMD.CommentRequired", "PMD.ShortVariable", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class RestartRuleAcceptanceTest {

  /** How long a run count must stay put to show that nothing asks for another run. */
  private static final Duration STABLE = Duration.ofMillis(1500);

  private static final Duration WAIT = Duration.ofSeconds(30);

  private static final String RULES_JSON =
      """
      {"rules":[
        {"id":"restart-on-retry",
         "when":{"event":"NodeStatusChanged","status":"SUCCESS","module":"OUTCOME_TERMINAL",
                 "if":"metadata['reached'] == true and metadata['outcome'] == 'RETRY' and attempt < 2"},
         "then":{"command":"RestartFromNode","from":"metadata.restartFrom"}}
      ]}
      """;

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;

  @DynamicPropertySource
  /* default */ static void rules(final DynamicPropertyRegistry registry) throws IOException {
    final Path file = Files.createTempFile("rules", ".json");
    Files.writeString(file, RULES_JSON);
    registry.add("yukta.supervisor.rules.file", file::toString);
  }

  private static Node shell(final String id, final String script) {
    return new Node(
        id,
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

  private static Node outcome(final String id, final String outcome) {
    final Map<String, Object> config =
        "RETRY".equals(outcome)
            ? Map.of("outcome", outcome, "restartFrom", "check")
            : Map.of("outcome", outcome);
    return new Node(id, "OUTCOME_TERMINAL", config);
  }

  /** Starts the stage; {@code applyScript} is what the repair does. */
  private String start(final Path dir, final String applyScript) {
    final String session = "restart-" + UUID.randomUUID();
    final String workflowId = "wf-" + UUID.randomUUID();
    final Path fixed = dir.resolve("fixed");
    final Path runs = dir.resolve("runs");
    final WorkflowDefinition definition =
        new WorkflowDefinition(
            workflowId,
            "stage",
            List.of(
                new Node("t", "CONSTANT_SOURCE", Map.of()),
                shell("check", "echo x >> '" + runs + "'; test -f '" + fixed + "'"),
                outcome("ok", "SUCCESS"),
                shell("apply", applyScript.replace("FIXED", fixed.toString())),
                outcome("retry", "RETRY"),
                outcome("escalate", "ESCALATE")),
            List.of(
                new Edge("t", "check", null),
                new Edge("check", "ok", "success"),
                new Edge("check", "apply", "failure"),
                new Edge("apply", "retry", "success"),
                new Edge("apply", "escalate", "failure")));
    definitions.save(session, definition).block();
    gateway.startWorkflow(session, workflowId).block();
    return runs.toString();
  }

  private static long runs(final String file) throws IOException {
    final Path path = Path.of(file);
    return Files.exists(path) ? Files.readAllLines(path).size() : 0;
  }

  @Test
  void retryOutcome_restartsFromCheck_andSucceeds(@TempDir final Path dir) throws IOException {
    final String runs = start(dir, "touch FIXED");

    await().atMost(WAIT).untilAsserted(() -> assertThat(runs(runs)).isEqualTo(2));
    // the second run passes the check, so nothing asks for a third
    await().during(STABLE).atMost(WAIT).untilAsserted(() -> assertThat(runs(runs)).isEqualTo(2));
    assertThat(runs(runs)).isEqualTo(2);
  }

  @Test
  void escalateOutcome_isNotRestarted(@TempDir final Path dir) throws IOException {
    final String runs = start(dir, "false");

    await().atMost(WAIT).untilAsserted(() -> assertThat(runs(runs)).isEqualTo(1));
    await().during(STABLE).atMost(WAIT).untilAsserted(() -> assertThat(runs(runs)).isEqualTo(1));
    assertThat(runs(runs)).isEqualTo(1);
  }

  @Test
  void retryOutcome_stopsWhenAttemptBudgetIsSpent(@TempDir final Path dir) throws IOException {
    // the repair claims success but never fixes anything, so every run asks for a retry
    final String runs = start(dir, "true");

    await().atMost(WAIT).untilAsserted(() -> assertThat(runs(runs)).isEqualTo(3));
    await().during(STABLE).atMost(WAIT).untilAsserted(() -> assertThat(runs(runs)).isEqualTo(3));
    assertThat(runs(runs)).isEqualTo(3);
  }
}
