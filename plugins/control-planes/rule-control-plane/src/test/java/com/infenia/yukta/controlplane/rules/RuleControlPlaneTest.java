// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.supervisor.rules.RuleSupervisor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

@NoArgsConstructor
class RuleControlPlaneTest {

  private static final String RULES =
      """
      {"rules": [
        {"id": "pause", "when": {"event": "NodeStatusChanged", "status": "FAILURE"},
         "then": {"command": "PauseWorkflow"}},
        {"id": "no-kill", "deny": {"command": "StopWorkflow", "if": "mode == 'KILL'",
         "reason": "use DRAIN"}}
      ]}
      """;

  private static final RuleControlPlane PLANE =
      new RuleControlPlane(RuleSupervisor.read(RULES), Clock.systemUTC());

  private static ExecutionEvent node(final String status) {
    return new ExecutionEvent.NodeStatusChanged(
        "e1", "s", "wf", "n1", "HTTP_REQUEST", status, Map.of(), Instant.now());
  }

  @Test
  void id_isRuleControlPlane() {
    assertThat(PLANE.id()).isEqualTo("rule-control-plane");
  }

  @Test
  void fileConstructor_loadsRules(@TempDir final Path dir) throws IOException {
    final Path file = Files.writeString(dir.resolve("rules.json"), RULES);

    final var plane = new RuleControlPlane(file);

    assertThat(plane.decide(node("FAILURE")).block().commands()).hasSize(1);
  }

  @Test
  void decide_matchingEvent_commandsWithRuleSource() {
    final var decision = PLANE.decide(node("FAILURE")).block();

    assertThat(decision.source()).isEqualTo(Source.RULE);
    assertThat(decision.confidence()).isEqualTo(1);
    assertThat(decision.commands())
        .containsExactly(new ExecutionControlCommand.PauseWorkflowCommand("e1"));
  }

  @Test
  void decide_otherEvent_noCommands() {
    assertThat(PLANE.decide(node("SUCCESS")).block().commands()).isEmpty();
  }

  @Test
  void supervise_hostedLikeAnySupervisor_emitsRuleCommands() {
    final var commands =
        PLANE.supervise(Flux.just(node("SUCCESS"), node("FAILURE"))).collectList().block();

    assertThat(commands).containsExactly(new ExecutionControlCommand.PauseWorkflowCommand("e1"));
  }

  @Test
  void review_denyRule_denied() {
    final var kill = new ExecutionControlCommand.StopWorkflowCommand("e1", "x", StopMode.KILL);

    final var verdict = PLANE.review(new ControlRequest(kill, null, null, null)).block();

    assertThat(verdict.allowed()).isFalse();
    assertThat(verdict.reason()).isEqualTo("use DRAIN");
  }
}
