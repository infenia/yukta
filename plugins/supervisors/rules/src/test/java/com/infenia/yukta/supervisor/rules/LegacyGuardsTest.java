// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The retired guards become rules supervisors with the same effect, or say what changed. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class LegacyGuardsTest {

  private final RuleSupervisorFactory factory = new RuleSupervisorFactory();

  /** A converted document must load as the rules engine reads it. */
  private static void loads(final Map<String, Object> rules) {
    new RuleSupervisor(RuleSupervisor.read(rules), Clock.systemUTC());
  }

  @Test
  void failureGuardKeepsCountWindowNodesErrorTextAndAction() {
    final LegacyGuards.Converted converted =
        LegacyGuards.failureGuard(
            Map.of(
                "failures",
                2,
                "withinSeconds",
                30,
                "nodes",
                List.of("build.*"),
                "errorContains",
                "OOM",
                "action",
                "STOP_WORKFLOW"));

    loads(converted.rules());
    assertThat(converted.notes()).isEmpty();
    assertThat(converted.rules().toString())
        .contains("count=2", "withinSeconds=30", "StopWorkflow", "build", "OOM");
    loads(LegacyGuards.failureGuard(Map.of()).rules());
    assertThatThrownBy(() -> LegacyGuards.failureGuard(Map.of("action", "EXPLODE")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void approvalGateHasOneApprovalRulePerCommand() {
    final LegacyGuards.Converted converted =
        LegacyGuards.approvalGate(
            Map.of(
                "commands",
                List.of("StopWorkflowCommand", "RestartCommand"),
                "nodes",
                List.of("deploy"),
                "risk",
                "HIGH",
                "route",
                "ops",
                "reason",
                "careful",
                "ttl",
                60));

    loads(converted.rules());
    assertThat((List<?>) converted.rules().get("rules")).hasSize(2);
    assertThatThrownBy(() -> LegacyGuards.approvalGate(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LegacyGuards.approvalGate(Map.of("commands", List.of("Nope"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stallWatchdogBecomesTimedRuleOverRunsAndSaysWhatChanged() {
    final LegacyGuards.Converted converted =
        LegacyGuards.stallWatchdog(
            Map.of(
                "maxRunningSeconds", 120,
                "maxSilenceSeconds", 30,
                "checkEverySeconds", 5,
                "nodes", List.of("slow"),
                "action", "PAUSE_NODE"));

    loads(converted.rules());
    assertThat(converted.rules().toString()).contains("every=5s", "idleSeconds > 120");
    assertThat(converted.notes()).hasSize(3);
    assertThat(LegacyGuards.stallWatchdog(Map.of("maxSilenceSeconds", 10)).notes()).hasSize(1);
    assertThatThrownBy(() -> LegacyGuards.stallWatchdog(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rulesEngineAdoptsTheRetiredRuleTypesAndGuardsAndKeepsCommonSettings() {
    final Supervisor oldRules =
        new Supervisor("r", "rule-control-plane", Map.of("rules", List.of()), true, "d");
    final SupervisorFactory.Adoption rules = factory.adopt(oldRules).orElseThrow();
    assertThat(rules.supervisor().type()).isEqualTo("rules");
    assertThat(rules.supervisor().dryRun()).isTrue();
    assertThat(rules.supervisor().description()).isEqualTo("d");

    final Supervisor guard =
        new Supervisor(
            "g",
            "failure-guard",
            Map.of("failures", 1, "allowedCommands", List.of("PauseWorkflowCommand")),
            false);
    final SupervisorFactory.Adoption adopted = factory.adopt(guard).orElseThrow();
    assertThat(adopted.supervisor().settings())
        .containsKey("rules")
        .containsEntry("allowedCommands", List.of("PauseWorkflowCommand"))
        .doesNotContainKey("failures");
    assertThat(
            factory.adopt(
                new Supervisor(
                    "a", "approval-gate", Map.of("commands", List.of("RestartCommand")), false)))
        .isPresent();
    assertThat(
            factory.adopt(
                new Supervisor("w", "stall-watchdog", Map.of("maxSilenceSeconds", 9), false)))
        .isPresent();
    assertThat(factory.adopt(new Supervisor("m", "model-control-plane", Map.of(), false)))
        .isEmpty();
  }

  @Test
  void anApprovalGateNeedsKnownCommandsToHold() {
    assertThatThrownBy(() -> LegacyGuards.approvalGate(Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commands is required");
    assertThatThrownBy(() -> LegacyGuards.approvalGate(Map.of("commands", List.of("Bogus"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commands must be among");
    assertThatThrownBy(() -> LegacyGuards.approvalGate(Map.of("commands", List.of(7))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void watchdogWithOnlyRunningLimitNeedsNoSilenceAndGuardTakesEveryActionName() {
    assertThat(LegacyGuards.stallWatchdog(Map.of("maxRunningSeconds", 60)).notes()).hasSize(1);
    for (final String action : new String[] {"PAUSE_WORKFLOW", "stop_workflow", "PAUSE_NODE"}) {
      loads(LegacyGuards.failureGuard(Map.of("action", action)).rules());
    }
    loads(LegacyGuards.failureGuard(Map.of()).rules());
    assertThatThrownBy(() -> LegacyGuards.failureGuard(Map.of("action", "RESTART")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown action");
  }
}
