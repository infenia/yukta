// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.PluginLogSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Sessions of the real CLIs, recorded and kept as fixtures, replayed through the shipped presets.
 * Both were recorded while the account behind the CLI could not pay, so they are the failure path:
 * what each CLI says when its provider refuses the request. A recording of a successful turn is
 * still to be made (see the preset descriptions).
 */
@NoArgsConstructor
class RecordedSessionsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final List<String> shown = new ArrayList<>();
  private final PluginLogSink log = (stream, line, level) -> shown.add(line);

  private AgentOutput replay(final String preset, final String fixture) throws IOException {
    final AgentOutput output =
        new AgentOutput(
            new com.infenia.yukta.plugin.agentsession.AgentHarnesses(JSON, "").get(preset).output(),
            log,
            JSON);
    try (var in = getClass().getResourceAsStream("/recorded/" + fixture)) {
      for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        output.line("stdout", line, null);
      }
    }
    return output;
  }

  @Test
  void codexTellsWhyExpiredKeyFailedTheTurn() throws IOException {
    final AgentOutput output = replay("codex", "codex/codex-0.169.0-expired-key.jsonl");

    assertThat(output.outcome(1, false, 60))
        .containsEntry("status", "failure")
        .extractingByKey("reason")
        .asString()
        .contains("401 Unauthorized")
        .contains("API key has expired");
    assertThat(output.outcome(1, false, 60))
        .containsEntry("sessionId", "01a1e476-d819-79e0-8f09-134201e721e6");
  }

  @Test
  void piTellsWhyProviderThatRefusedPaymentFailedTheTurn() throws IOException {
    final AgentOutput output = replay("pi", "pi/pi-1.5.2-insufficient-balance.jsonl");

    assertThat(output.outcome(0, false, 60))
        .containsEntry("status", "failure")
        .extractingByKey("reason")
        .asString()
        .contains("402 Payment Required")
        .contains("insufficient_balance");
  }

  @Test
  void piEndsTurnWithItsAgentEndAndKeepsTheLastAnswer() throws IOException {
    final AgentOutput output =
        new AgentOutput(
            new com.infenia.yukta.plugin.agentsession.AgentHarnesses(JSON, "").get("pi").output(),
            log,
            JSON);
    output.line(
        "stdout",
        "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"stop\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}}",
        null);
    output.line("stdout", "{\"type\":\"agent_end\",\"messages\":[]}", null);

    assertThat(output.outcome(0, false, 60))
        .containsEntry("status", "success")
        .containsEntry("text", "2");
    assertThat(shown).contains("2");
  }
}
