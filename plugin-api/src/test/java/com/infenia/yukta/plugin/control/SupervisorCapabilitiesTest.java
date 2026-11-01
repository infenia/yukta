// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.input.InputRisk;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SupervisorCapabilitiesTest {

  private static SupervisorProfile profile(final Map<String, Object> settings) {
    return new SupervisorProfile("p", "rules", settings, false);
  }

  @Test
  void withNoSettingsEverythingOldIsAllowedAndNothingNewIs() {
    final SupervisorCapabilities caps = SupervisorCapabilities.from(profile(Map.of()));
    assertThat(caps.allowedCommands()).isNull();
    assertThat(caps.canAnswer()).isFalse();
    assertThat(caps.maxRisk()).isEqualTo(InputRisk.LOW);
    assertThat(caps.allows(new ExecutionControlCommand.StopWorkflowCommand("e", "r"))).isTrue();
    assertThat(caps.mayAnswer(InputRisk.LOW)).isFalse();
  }

  @Test
  void theFactoryDefaultAppliesWhenTheProfileDoesNotSay() {
    final SupervisorCapabilities none = SupervisorCapabilities.from(profile(Map.of()), Set.of());
    assertThat(none.allows(new ExecutionControlCommand.PauseWorkflowCommand("e"))).isFalse();
    final SupervisorCapabilities profileWins =
        SupervisorCapabilities.from(
            profile(Map.of("allowedCommands", List.of("PauseWorkflowCommand"))), Set.of());
    assertThat(profileWins.allows(new ExecutionControlCommand.PauseWorkflowCommand("e"))).isTrue();
  }

  @Test
  void readsEverySetting() {
    final SupervisorCapabilities caps =
        SupervisorCapabilities.from(
            profile(
                Map.of(
                    "allowedCommands",
                    List.of("PauseWorkflowCommand", "AnswerInputCommand"),
                    "canAnswer",
                    true,
                    "maxRisk",
                    "medium")));
    assertThat(caps.allowedCommands())
        .containsExactlyInAnyOrder("PauseWorkflowCommand", "AnswerInputCommand");
    assertThat(caps.allows(new ExecutionControlCommand.PauseWorkflowCommand("e"))).isTrue();
    assertThat(caps.allows(new ExecutionControlCommand.StopWorkflowCommand("e", "r"))).isFalse();
    assertThat(caps.mayAnswer(InputRisk.LOW)).isTrue();
    assertThat(caps.mayAnswer(InputRisk.MEDIUM)).isTrue();
    assertThat(caps.mayAnswer(InputRisk.HIGH)).isFalse();
  }

  @Test
  void flagMayBeTheStringTrueOrFalse() {
    assertThat(SupervisorCapabilities.from(profile(Map.of("canAnswer", "TRUE"))).canAnswer())
        .isTrue();
    assertThat(SupervisorCapabilities.from(profile(Map.of("canAnswer", "false"))).canAnswer())
        .isFalse();
    assertThatThrownBy(() -> SupervisorCapabilities.from(profile(Map.of("canAnswer", "yes"))))
        .hasMessage("canAnswer must be true or false");
    assertThatThrownBy(() -> SupervisorCapabilities.from(profile(Map.of("canAnswer", 1))))
        .hasMessage("canAnswer must be true or false");
  }

  @Test
  void badSettingsAreRefusedWithClearMessage() {
    assertThatThrownBy(() -> SupervisorCapabilities.from(profile(Map.of("maxRisk", "huge"))))
        .hasMessage("maxRisk must be LOW, MEDIUM or HIGH, not 'huge'");
    assertThatThrownBy(() -> SupervisorCapabilities.from(profile(Map.of("maxRisk", 3))))
        .hasMessage("maxRisk must be LOW, MEDIUM or HIGH");
    assertThatThrownBy(
            () -> SupervisorCapabilities.from(profile(Map.of("allowedCommands", "Pause"))))
        .hasMessage("allowedCommands must be a list of command names");
    assertThatThrownBy(
            () ->
                SupervisorCapabilities.from(
                    profile(Map.of("allowedCommands", List.of("NukeEverythingCommand")))))
        .hasMessageContaining("Unknown command in allowedCommands: 'NukeEverythingCommand'")
        .hasMessageContaining("AnswerInputCommand");
    assertThatThrownBy(
            () -> SupervisorCapabilities.from(profile(Map.of("allowedCommands", List.of(7)))))
        .hasMessageContaining("Unknown command in allowedCommands: '7'");
  }

  @Test
  void theCommandNamesCoverEveryPermittedCommand() {
    final SupervisorCapabilities caps =
        SupervisorCapabilities.from(
            profile(
                Map.of(
                    "allowedCommands",
                    java.util.Arrays.stream(ExecutionControlCommand.class.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .toList())));
    assertThat(caps.allowedCommands()).hasSize(18);
  }

  @Test
  void actorKindsAreNamed() {
    assertThat(ControlActor.KIND_KEY).isEqualTo("yukta.control.actor.kind");
    assertThat(ControlActor.Kind.values())
        .containsExactly(
            ControlActor.Kind.SUPERVISOR, ControlActor.Kind.KEY, ControlActor.Kind.TOKEN);
  }
}
