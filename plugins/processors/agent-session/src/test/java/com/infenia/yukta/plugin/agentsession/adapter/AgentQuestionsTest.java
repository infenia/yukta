// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.Responder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PROTOTYPE of ADR 0013: replays the recorded sessions. The question is captured from what the
 * agent printed, turned into an input request, answered, and the reply must equal the reply the
 * real agent accepted in the recording.
 */
class AgentQuestionsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static List<JsonNode> frames(final String path) throws IOException {
    try (var in =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(AgentQuestionsTest.class.getResourceAsStream(path)),
                StandardCharsets.UTF_8))) {
      return in.lines().map(JSON::readTree).toList();
    }
  }

  private static JsonNode first(final List<JsonNode> frames, final Predicate<JsonNode> p) {
    return frames.stream().filter(p).findFirst().orElseThrow().path("frame");
  }

  private static InputResponse answer(
      final String decision, final String text, final Map<String, Object> data) {
    return new InputResponse(
        "r1", decision, text, data, Responder.SYSTEM, null, null, Instant.EPOCH);
  }

  private static void claude(final String scenario, final List<InputResponse> answers)
      throws IOException {
    final List<JsonNode> f =
        frames("/agent-sessions/claude-code/ask-user-question-" + scenario + ".jsonl");
    final JsonNode req =
        first(f, n -> "control_request".equals(n.path("frame").path("type").asString()));
    final JsonNode sent =
        first(f, n -> "control_response".equals(n.path("frame").path("type").asString()));
    final var questions = AgentQuestions.fromClaude(req.path("request").path("input"));
    questions.forEach(q -> AgentQuestions.spec(q, "k", Duration.ofMinutes(5)));

    final String reply =
        AgentQuestions.claudeReply(
            JSON,
            req.path("request_id").asString(),
            req.path("request").path("input"),
            questions,
            answers);

    assertThat(JSON.readTree(reply)).isEqualTo(sent);
  }

  private static void acp(final String scenario, final InputResponse answer) throws IOException {
    final List<JsonNode> f =
        frames("/agent-sessions/antigravity-acp/elicitation-" + scenario + ".jsonl");
    final JsonNode ask =
        first(f, n -> "elicitation/create".equals(n.path("frame").path("method").asString()));
    final JsonNode sent =
        first(
            f,
            n ->
                "in".equals(n.path("dir").asString())
                    && n.path("frame").has("result")
                    && n.path("frame").path("id").equals(ask.path("id")));
    final var questions =
        AgentQuestions.fromElicitation(ask.path("params").path("requestedSchema"));

    assertThat(AgentQuestions.elicitationResult(JSON, questions, List.of(answer)))
        .isEqualTo(sent.path("result"));
  }

  @Test
  void claudeSingle() throws IOException {
    claude("single", List.of(answer("2", null, Map.of())));
  }

  @Test
  void claudeMulti() throws IOException {
    claude("multi", List.of(answer("selected", null, Map.of("selected", List.of("1", "2")))));
  }

  @Test
  void claudeFreeText() throws IOException {
    claude("other", List.of(answer("text", "SQLite, embedded", Map.of())));
  }

  @Test
  void claudeTwoQuestions() throws IOException {
    claude("several", List.of(answer("2", null, Map.of()), answer("2", null, Map.of())));
  }

  @Test
  void claudeQuestion_isAChoiceOnTodaysContract() throws IOException {
    final var q =
        AgentQuestions.fromClaude(
                first(
                        frames("/agent-sessions/claude-code/ask-user-question-multi.jsonl"),
                        n -> "control_request".equals(n.path("frame").path("type").asString()))
                    .path("request")
                    .path("input"))
            .get(0);

    final var spec = AgentQuestions.spec(q, "k", Duration.ofMinutes(5));

    assertThat(spec.kind()).isEqualTo(InputKind.CHOICE);
    assertThat(spec.options()).extracting("label").containsExactly("Lint", "Test", "Build");
    assertThat(spec.context()).containsEntry("multiSelect", true);
  }

  @Test
  void acpSingle() throws IOException {
    acp("single", answer("2", null, Map.of()));
  }

  @Test
  void acpMulti() throws IOException {
    acp("multi", answer("selected", null, Map.of("selected", List.of("1", "2"))));
  }

  @Test
  void acpFreeText() throws IOException {
    acp("other", answer("text", "SQLite, embedded", Map.of()));
  }
}
