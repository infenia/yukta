// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.guards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The approval gate parks the chosen commands and sends none of its own. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ApprovalGateFactoryTest {

  private static final ExecutionControlCommand STOP =
      new ExecutionControlCommand.StopWorkflowCommand("e1", "r", StopMode.DRAIN);

  private static final ExecutionControlCommand STOP_NODE =
      new ExecutionControlCommand.StopNodeCommand("e1", "build.a", false, null);

  private final ApprovalGateFactory factory = new ApprovalGateFactory();

  private ExecutionSupervisor gate(final Map<String, Object> settings) {
    return factory.create(new Supervisor("g", ApprovalGateFactory.TYPE, settings, false));
  }

  private static Decision review(
      final ExecutionSupervisor gate, final ExecutionControlCommand command, final String nodeId) {
    return gate.review(new ControlRequest(command, "ai", "s", "w", nodeId)).block();
  }

  @Test
  void parksTheChosenCommandsOnlyWithTheConfiguredNeed() {
    final ExecutionSupervisor gate =
        gate(
            Map.of(
                "commands",
                List.of("StopWorkflowCommand"),
                "risk",
                "medium",
                "route",
                "ops",
                "ttl",
                120,
                "reason",
                "ask ops"));

    final Decision parked = review(gate, STOP, null);

    assertThat(parked.requiresApproval()).isTrue();
    assertThat(parked.reason()).isEqualTo("ask ops");
    assertThat(parked.approval().risk()).isEqualTo(InputRisk.MEDIUM);
    assertThat(parked.approval().routeName()).isEqualTo("ops");
    assertThat(parked.approval().ttl()).isEqualTo(Duration.ofSeconds(120));
    assertThat(review(gate, new ExecutionControlCommand.PauseWorkflowCommand("e1"), null))
        .isEqualTo(Decision.ALLOW);
  }

  @Test
  void defaultsAreHighRiskAndNoCommandIsEverSent() {
    final ExecutionSupervisor gate = gate(Map.of("commands", List.of("StopWorkflowCommand")));

    assertThat(review(gate, STOP, null).approval().risk()).isEqualTo(InputRisk.HIGH);
    assertThat(factory.defaultAllowedCommands()).isEmpty();
    StepVerifier.create(gate.supervise(Flux.empty())).verifyComplete();
  }

  @Test
  void nodeSelectorsLimitTheGateToCommandsForThoseNodes() {
    final ExecutionSupervisor gate =
        gate(Map.of("commands", List.of("StopNodeCommand"), "nodes", List.of("build.*")));

    assertThat(review(gate, STOP_NODE, "build.a").requiresApproval()).isTrue();
    assertThat(
            review(
                gate,
                new ExecutionControlCommand.StopNodeCommand("e1", "deploy", false, null),
                "deploy"))
        .isEqualTo(Decision.ALLOW);
  }

  @Test
  void badSettingsAreRefused() {
    assertThatThrownBy(() -> gate(Map.of())).hasMessageContaining("commands is required");
    assertThatThrownBy(() -> gate(Map.of("commands", List.of("PauseWorkflowCommand"))))
        .hasMessageContaining("commands must be among");
    assertThatThrownBy(() -> gate(Map.of("commands", List.of(1))))
        .hasMessageContaining("commands must be among");
    assertThatThrownBy(
            () -> gate(Map.of("commands", List.of("StopWorkflowCommand"), "risk", "SEVERE")))
        .hasMessageContaining("risk");
    assertThatThrownBy(() -> gate(Map.of("commands", List.of("StopWorkflowCommand"), "ttl", 0)))
        .hasMessageContaining("ttl");
    assertThatThrownBy(
            () -> gate(Map.of("commands", List.of("StopWorkflowCommand"), "route", "no spaces")))
        .hasMessageContaining("route");
  }

  @Test
  void catalogEntryIsCompleteAndItsExampleMakesGate() {
    final var descriptor = factory.describe();

    assertThat(descriptor.displayName()).isEqualTo("Approval gate");
    assertThat(descriptor.category().name()).isEqualTo("GATE");
    assertThat(descriptor.uiDesign()).isPresent();
    assertThat(factory.getConfigSchema())
        .extracting(ConfigField::name)
        .containsExactly("commands", "nodes", "risk", "route", "ttl", "reason");
    factory.validate(new Supervisor("g", factory.type(), descriptor.exampleSettings(), false));
  }
}
