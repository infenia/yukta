// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.PluginLogSink;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** How a harness' output lines are read: what is shown, kept and reported. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AgentOutputTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final List<String> shown = new CopyOnWriteArrayList<>();
  private final PluginLogSink log = (stream, line, level) -> shown.add(line);

  private static AgentPreset preset(final String id) {
    try {
      return new AgentHarnesses(JSON, "").get(id);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void timedOutRun_failsNamingTheTimeout() {
    final AgentOutput output = new AgentOutput(preset("claude-code").output(), log, JSON);

    assertThat(output.outcome(137, true, 5))
        .containsEntry("status", "failure")
        .containsEntry("reason", "timed out after 5s")
        .containsEntry("exitCode", 137);
  }

  @Test
  void lineThatOnlyLooksLikeJson_isShownAsItIs() {
    final AgentOutput output = new AgentOutput(preset("claude-code").output(), log, JSON);

    output.line("stdout", "{not json", null);

    assertThat(shown).containsExactly("{not json");
  }

  @Test
  void errorResultWithoutText_givesTheResultKindAsTheReason() {
    final AgentOutput output = new AgentOutput(preset("claude-code").output(), log, JSON);

    output.line(
        "stdout",
        "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
            + "\"result\":\"\",\"session_id\":\"s9\"}");

    assertThat(output.outcome(1, false, 60))
        .containsEntry("status", "failure")
        .containsEntry("reason", "error_during_execution")
        .containsEntry("sessionId", "s9");
  }

  @Test
  void captureWithEmptyValue_keepsNothing() {
    final AgentOutput output = new AgentOutput(preset("codex").output(), log, JSON);

    output.line("stdout", "{\"type\":\"thread.started\",\"thread_id\":\"\"}");
    output.line("stdout", "{\"type\":\"turn.completed\"}");

    assertThat(output.outcome(0, false, 60)).containsEntry("sessionId", "");
  }

  @Test
  void requestWithNobodyToHandleIt_isNotShown() {
    final AgentPreset claude = preset("claude-code");
    final AgentOutput output = new AgentOutput(claude.output(), claude.interactive(), log, JSON);

    output.line(
        "stdout",
        "{\"type\":\"control_request\",\"request_id\":\"r\","
            + "\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\"}}");
    output.converse(request -> shown.add("handled " + request.tool()), () -> shown.add("done"));
    output.line("stdout", "{\"type\":\"system\",\"subtype\":\"init\"}");
    output.line(
        "stdout",
        "{\"type\":\"control_request\",\"request_id\":\"r\","
            + "\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\"}}");

    assertThat(shown).containsExactly("handled Bash");
  }
}
