// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** The {@code requireApproval} rule action. */
class RuleSupervisorApprovalTest {

  private static final ExecutionControlCommand STOP =
      new ExecutionControlCommand.StopWorkflowCommand("e1", "r", StopMode.DRAIN);

  private static RuleSupervisor supervisor(final String rulesJson) {
    return new RuleSupervisor(RuleSupervisor.read(rulesJson), Clock.systemUTC());
  }

  private static Decision review(
      final RuleSupervisor supervisor,
      final ExecutionControlCommand command,
      final String workflowId) {
    return supervisor.review(new ControlRequest(command, "ai", "s", workflowId)).block();
  }

  @Test
  void stopOfTheProductionWorkflowNeedsApproval() {
    final RuleSupervisor rules =
        supervisor(
            """
            {"rules":[{"id":"prod-stop","requireApproval":{"command":"StopWorkflow",
              "if":"workflowId == 'prod-deploy'","risk":"medium","route":"ops",
              "reason":"production stop"}}]}
            """);
    final Decision gated = review(rules, STOP, "prod-deploy");
    assertThat(gated.allowed()).isFalse();
    assertThat(gated.requiresApproval()).isTrue();
    assertThat(gated.reason()).isEqualTo("production stop");
    assertThat(gated.approval().risk()).isEqualTo(InputRisk.MEDIUM);
    assertThat(gated.approval().routeName()).isEqualTo("ops");
    assertThat(review(rules, STOP, "other")).isEqualTo(Decision.ALLOW);
    assertThat(review(rules, new ExecutionControlCommand.PauseWorkflowCommand("e1"), "prod-deploy"))
        .isEqualTo(Decision.ALLOW);
  }

  @Test
  void theDefaultsAreHighRiskTheDefaultRouteAndReasonNamingTheRule() {
    final Decision gated =
        review(
            supervisor(
                "{\"rules\":[{\"id\":\"gate\","
                    + "\"requireApproval\":{\"command\":\"StopWorkflow\"}}]}"),
            STOP,
            "wf");
    assertThat(gated.approval().risk()).isEqualTo(InputRisk.HIGH);
    assertThat(gated.approval().routeName()).isNull();
    assertThat(gated.reason()).isEqualTo("approval required by rule gate");
  }

  @Test
  void denialWinsOverNeedForApproval() {
    final RuleSupervisor rules =
        supervisor(
            """
            {"rules":[
              {"id":"gate","requireApproval":{"command":"StopWorkflow"}},
              {"id":"no-kill","deny":{"command":"StopWorkflow","if":"mode == 'KILL'",
                "reason":"use DRAIN"}}]}
            """);
    assertThat(
            review(
                rules,
                new ExecutionControlCommand.StopWorkflowCommand("e1", "r", StopMode.KILL),
                "w"))
        .isEqualTo(Decision.deny("use DRAIN"));
    assertThat(review(rules, STOP, "w").requiresApproval()).isTrue();
  }

  @Test
  void badRiskRouteOrMixOfActionsIsRefusedAtLoad() {
    assertThatThrownBy(
            () ->
                supervisor(
                    "{\"rules\":[{\"id\":\"r\",\"requireApproval\":{\"command\":\"StopWorkflow\","
                        + "\"risk\":\"SEVERE\"}}]}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Rule 'r': risk must be LOW, MEDIUM or HIGH");
    assertThatThrownBy(
            () ->
                supervisor(
                    "{\"rules\":[{\"id\":\"r\",\"requireApproval\":{\"command\":\"StopWorkflow\","
                        + "\"route\":\"no spaces\"}}]}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Rule 'r': routeName");
    assertThatThrownBy(
            () ->
                supervisor(
                    "{\"rules\":[{\"id\":\"r\",\"requireApproval\":{\"command\":\"StopWorkflow\"},"
                        + "\"deny\":{\"command\":\"StopWorkflow\"}}]}"))
        .hasMessageContaining("Rule 'r' needs either");
    assertThatThrownBy(
            () ->
                supervisor("{\"rules\":[{\"id\":\"r\",\"requireApproval\":{\"command\":\" \"}}]}"))
        .hasMessageContaining("must name the command");
    assertThatThrownBy(
            () ->
                supervisor(
                    "{\"rules\":[{\"id\":\"r\",\"requireApproval\":{\"command\":\"StopWorkflow\","
                        + "\"unknown\":1}}]}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theFourPartRuleHasNoGate() {
    assertThat(new RuleSet.Rule("r", null, null, null).requireApproval()).isNull();
  }
}
