// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.agentsession.AgentHarnesses;
import com.infenia.yukta.plugin.agentsession.AgentPreset;
import com.infenia.yukta.plugin.core.PluginLogSink;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** How a harness' output lines are read: what is shown, kept and reported. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
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
  void extras_carryCostTurnsAndTheStructuredAnswerWithTheirTypes() {
    final AgentOutput output = new AgentOutput(preset("claude-code").output(), log, JSON);

    output.line(
        "stdout",
        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\","
            + "\"session_id\":\"s1\",\"total_cost_usd\":0.25,\"num_turns\":3,"
            + "\"structured_output\":{\"claimed\":true,\"n\":[1,2]}}");

    assertThat(output.outcome(0, false, 60))
        .containsEntry("status", "success")
        .containsEntry("costUsd", 0.25)
        .containsEntry("numTurns", 3)
        .containsEntry("structured", Map.of("claimed", true, "n", List.of(1, 2)));
  }

  @Test
  void extrasTheResultLacks_areLeftOut() {
    final AgentOutput output = new AgentOutput(preset("claude-code").output(), log, JSON);

    output.line("stdout", "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\"}");

    assertThat(output.outcome(0, false, 60))
        .doesNotContainKeys("costUsd", "numTurns", "structured");
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

  @Test
  void linesRecordedFromTheRealClaudeCode_areRead() throws IOException {
    final AgentPreset claude = new AgentHarnesses(JSON, "").get("claude-code");
    final List<String> log = new CopyOnWriteArrayList<>();
    final AgentOutput output =
        new AgentOutput(claude.output(), (stream, line, level) -> log.add(line), JSON);

    // recorded from `claude -p ... --output-format stream-json --verbose` (2.1.322)
    output.line("stdout", "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"108aa031\"}");
    output.line(
        "stdout",
        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
            + "\"session_id\":\"108aa031\"}");
    output.line(
        "stdout",
        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"hi\","
            + "\"session_id\":\"108aa031\"}");

    assertThat(log).containsExactly("hi");
    assertThat(output.outcome(0, false, 600))
        .containsEntry("status", "success")
        .containsEntry("text", "hi")
        .containsEntry("sessionId", "108aa031");
  }

  @Test
  void theCodexPreset_readsTheThreadAndTheLastAgentMessage() throws IOException {
    final AgentPreset codex = new AgentHarnesses(JSON, "").get("codex");
    final List<String> log = new CopyOnWriteArrayList<>();
    final AgentOutput output =
        new AgentOutput(codex.output(), (stream, line, level) -> log.add(line), JSON);

    // the shape documented for `codex exec --json`; not recorded from a real run
    output.line("stdout", "{\"type\":\"thread.started\",\"thread_id\":\"th-1\"}");
    output.line("stdout", "{\"type\":\"turn.started\"}");
    output.line(
        "stdout",
        "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"command_execution\","
            + "\"command\":\"ls\"}}");
    output.line(
        "stdout",
        "{\"type\":\"item.completed\",\"item\":{\"id\":\"i2\",\"type\":\"agent_message\","
            + "\"text\":\"all done\"}}");
    output.line(
        "stdout",
        "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}");

    assertThat(log).containsExactly("run: ls", "all done");
    assertThat(output.outcome(0, false, 600))
        .containsEntry("status", "success")
        .containsEntry("text", "all done")
        .containsEntry("sessionId", "th-1");
  }

  @Test
  void theCodexPreset_failedTurnIsFailureWithItsReason() throws IOException {
    final AgentPreset codex = new AgentHarnesses(JSON, "").get("codex");
    final AgentOutput output = new AgentOutput(codex.output(), PluginLogSink.NOOP, JSON);

    output.line("stdout", "{\"type\":\"thread.started\",\"thread_id\":\"th-2\"}");
    output.line("stdout", "{\"type\":\"turn.failed\",\"error\":{\"message\":\"quota exceeded\"}}");

    assertThat(output.outcome(1, false, 600))
        .containsEntry("status", "failure")
        .containsEntry("reason", "quota exceeded")
        .containsEntry("sessionId", "th-2");
  }

  @Test
  void lineOnStandardErrorIsTheNoticeWhoseLevelFollowsTheLineAndDefaultsToWarn()
      throws IOException {
    final RecordingConversation conversation = new RecordingConversation();
    final AgentOutput output =
        new AgentOutput(
            new AgentHarnesses(JSON, "").get("claude-code").output(),
            null,
            new LogMirror(PluginLogSink.NOOP),
            conversation,
            JSON);

    output.line("stderr", "plain");
    output.line("stderr", "warned", com.infenia.yukta.plugin.core.LogLevel.WARN);
    output.line("stderr", "broken", com.infenia.yukta.plugin.core.LogLevel.ERROR);
    output.line("stderr", "fyi", com.infenia.yukta.plugin.core.LogLevel.INFO);
    output.line("stdout", "not json, not an error");

    assertThat(conversation.calls())
        .containsExactly(
            "emit NOTICE null {level=WARN, text=plain}",
            "emit NOTICE null {level=WARN, text=warned}",
            "emit NOTICE null {level=ERROR, text=broken}",
            "emit NOTICE null {level=INFO, text=fyi}");
  }
}
