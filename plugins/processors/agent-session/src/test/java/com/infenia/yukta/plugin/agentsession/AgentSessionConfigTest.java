// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The parsing and checks of an AGENT_SESSION node's configuration. */
@NoArgsConstructor
class AgentSessionConfigTest {
  private static final String APPROVAL_TIMEOUT = "approvalTimeout";

  private final AgentHarnesses harnesses = shipped();
  private final AgentSessionConfig config = new AgentSessionConfig(harnesses);

  private static AgentHarnesses shipped() {
    try {
      return new AgentHarnesses(new ObjectMapper(), "");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void validate_acceptsTheOptionsHarnessSupports() {
    final Map<String, Object> options =
        Map.of(
            "harness", "claude-code",
            "prompt", "p",
            "maxTurns", 3,
            "session", "k",
            "args", List.of("--allowedTools", "Read"));

    assertThatCode(() -> config.validate(options)).doesNotThrowAnyException();
  }

  @Test
  void validate_namesWhatIsMissing() {
    assertThatThrownBy(() -> config.validate(Map.of("harness", "codex")))
        .hasMessage("prompt is mandatory");
    assertThatThrownBy(() -> config.validate(Map.of("prompt", "p")))
        .hasMessageStartingWith("harness is mandatory; one of [claude-code, codex");
  }

  @Test
  void validate_refusesArgsThatAreNotList() {
    assertThatThrownBy(
            () -> config.validate(Map.of("harness", "codex", "prompt", "p", "args", "--x")))
        .hasMessage("args must be a list of strings");
  }

  @Test
  void approvalTimeout_mustBePositiveNumber() {
    assertThatThrownBy(() -> AgentSessionConfig.settings(Map.of(APPROVAL_TIMEOUT, "soon")))
        .hasMessage("approvalTimeout must be positive, got: soon");
    assertThatThrownBy(() -> AgentSessionConfig.settings(Map.of(APPROVAL_TIMEOUT, -1)))
        .hasMessage("approvalTimeout must be positive, got: -1");
    assertThatThrownBy(() -> AgentSessionConfig.settings(Map.of(APPROVAL_TIMEOUT, 0)))
        .hasMessage("approvalTimeout must be positive, got: 0");
    assertThat(AgentSessionConfig.settings(Map.of(APPROVAL_TIMEOUT, 60)).ttl())
        .isEqualTo(Duration.ofSeconds(60));
    assertThat(AgentSessionConfig.settings(Map.of()).ttl()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void timeout_mustBePositiveNumber() {
    assertThatThrownBy(() -> AgentSessionConfig.timeoutSeconds(Map.of("timeout", "long")))
        .hasMessage("timeout must be positive, got: long");
    assertThatThrownBy(() -> AgentSessionConfig.timeoutSeconds(Map.of("timeout", 0)))
        .hasMessage("timeout must be positive, got: 0");
    assertThat(AgentSessionConfig.timeoutSeconds(Map.of("timeout", 15))).isEqualTo(15);
    assertThat(AgentSessionConfig.timeoutSeconds(Map.of())).isEqualTo(600);
  }

  @Test
  void command_addsResumeModelTurnLimitAndExtraArguments() {
    final AgentPreset claude = harnesses.get("claude-code");
    final AgentSessionPlugin.Turn turn =
        new AgentSessionPlugin.Turn("fix it", "", "opus", Map.of());

    final List<String> command =
        AgentSessionConfig.command(
            claude.command(),
            claude,
            Map.of("maxTurns", 4, "args", List.of("--allowedTools", "Read")),
            turn,
            "tok");

    assertThat(command)
        .containsSubsequence("claude", "-p", "fix it")
        .containsSubsequence("--resume", "tok", "--model", "opus", "--max-turns", "4")
        .endsWith("--allowedTools", "Read");
  }

  @Test
  void command_isTheBareCommandWithoutOptions() {
    final AgentPreset claude = harnesses.get("claude-code");
    final AgentSessionPlugin.Turn turn = new AgentSessionPlugin.Turn("go", "", "", Map.of());

    assertThat(AgentSessionConfig.command(claude.command(), claude, Map.of(), turn, null))
        .startsWith("claude", "-p", "go")
        .doesNotContain("--resume", "--model", "--max-turns");
    assertThat(AgentSessionConfig.command(claude.command(), claude, Map.of(), turn, " "))
        .doesNotContain("--resume");
  }

  @Test
  void validate_refusesConversationWithAHarnessThatTakesNoMessages() {
    assertThatThrownBy(() ->
            config.validate(Map.of("harness", "codex", "prompt", "p", "mode", "CONVERSATION")))
        .hasMessageContaining("harness 'codex'")
        .hasMessageContaining("no interactive mode")
        .hasMessageEndingWith("remove mode");
    assertThatThrownBy(
            () -> config.validate(Map.of("harness", "codex", "prompt", "p", "mode", "X")))
        .hasMessage("mode must be TASK or CONVERSATION, got: X");
  }

  @Test
  void validate_acceptsConversationWithAnInteractiveHarness() {
    assertThatCode(() ->
            config.validate(
                Map.of(
                    "harness",
                    "claude-code",
                    "prompt",
                    "p",
                    "mode",
                    "conversation",
                    "idleTimeout",
                    60,
                    "conversationTimeout",
                    600,
                    "steerAudience",
                    List.of("key:ann", "supervisor:*"))))
        .doesNotThrowAnyException();
  }

  @Test
  void validate_refusesTimeoutsThatAreNotPositiveNumbers() {
    assertThatThrownBy(() ->
            config.validate(Map.of("harness", "claude-code", "prompt", "p", "idleTimeout", 0)))
        .hasMessage("idleTimeout must be positive, got: 0");
    assertThatThrownBy(() ->
            config.validate(
                Map.of("harness", "claude-code", "prompt", "p", "conversationTimeout", "x")))
        .hasMessage("conversationTimeout must be positive, got: x");
  }

  @Test
  void conversationTimesOutByItsConversationTimeoutAndWaitsForMessagesByTheIdleTimeout() {
    assertThat(AgentSessionConfig.timeoutSeconds(Map.of("timeout", 5))).isEqualTo(5);
    assertThat(AgentSessionConfig.timeoutSeconds(Map.of("mode", "CONVERSATION", "timeout", 5)))
        .isEqualTo(14_400);
    assertThat(
            AgentSessionConfig.timeoutSeconds(
                Map.of("mode", "CONVERSATION", "conversationTimeout", 90)))
        .isEqualTo(90);
    assertThat(AgentSessionConfig.idleTimeout(Map.of()))
        .isEqualTo(java.time.Duration.ofSeconds(900));
    assertThat(AgentSessionConfig.idleTimeout(Map.of("idleTimeout", 30)))
        .isEqualTo(java.time.Duration.ofSeconds(30));
  }

  @Test
  void theSteerAudienceDefaultsToOperators() {
    assertThat(AgentSessionConfig.steerAudience(Map.of()).selectors())
        .containsExactly("role:OPERATOR");
    assertThat(
            AgentSessionConfig.steerAudience(Map.of("steerAudience", List.of("key:a"))).selectors())
        .containsExactly("key:a");
  }
}
