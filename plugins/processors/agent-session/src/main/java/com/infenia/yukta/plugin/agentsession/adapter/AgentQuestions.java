// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PROTOTYPE of ADR 0013 (#2706), not for merge: the canonical question model and the two protocol
 * responders, Claude Code's {@code AskUserQuestion} (jsonl) and ACP {@code elicitation/create}.
 */
public final class AgentQuestions {

  private static final String OTHER_SUFFIX = "_other";

  private AgentQuestions() {}

  /** One option; {@code id} is what an answer names. */
  public record Option(String id, String label, String description) {}

  /** One question as the agent asked it; {@code key} is how the agent names it in the answer. */
  public record Question(
      String key,
      String header,
      String text,
      List<Option> options,
      boolean multiSelect,
      boolean allowText) {}

  /** Claude Code: the {@code input} of a {@code can_use_tool} for {@code AskUserQuestion}. */
  public static List<Question> fromClaude(final JsonNode input) {
    final List<Question> out = new ArrayList<>();
    for (final JsonNode q : input.path("questions")) {
      final List<Option> options = new ArrayList<>();
      for (final JsonNode o : q.path("options")) {
        options.add(
            new Option(
                String.valueOf(options.size() + 1),
                o.path("label").asString(),
                o.path("description").asString("")));
      }
      final String text = q.path("question").asString();
      out.add(
          new Question(
              text,
              q.path("header").asString(""),
              text,
              options,
              q.path("multiSelect").asBoolean(false),
              true));
    }
    return out;
  }

  /** ACP: the {@code requestedSchema} of a form {@code elicitation/create}. */
  public static List<Question> fromElicitation(final JsonNode schema) {
    final JsonNode props = schema.path("properties");
    final List<Question> out = new ArrayList<>();
    for (final var e : props.properties()) {
      final String name = e.getKey();
      if (name.endsWith(OTHER_SUFFIX)
          && props.has(name.substring(0, name.length() - OTHER_SUFFIX.length()))) {
        continue;
      }
      final JsonNode p = e.getValue();
      final boolean multi = "array".equals(p.path("type").asString());
      final List<Option> options = new ArrayList<>();
      for (final JsonNode o : multi ? p.path("items").path("anyOf") : p.path("oneOf")) {
        options.add(
            new Option(
                o.path("const").asString(),
                o.path("title").asString(),
                o.path("description").asString("")));
      }
      out.add(
          new Question(
              name,
              "",
              p.path("title").asString(name),
              options,
              multi,
              options.isEmpty() || props.has(name + OTHER_SUFFIX)));
    }
    return out;
  }

  /**
   * The input request for one question, on today's contract: CHOICE with ids and labels when it has
   * two or more options, else TEXT. Lost on today's contract: option descriptions, multiSelect and
   * "free text allowed" (ADR 0013 adds them).
   */
  public static InputRequestSpec spec(final Question q, final String key, final Duration ttl) {
    final boolean choice = q.options().size() >= 2;
    return new InputRequestSpec(
        key,
        choice ? InputKind.CHOICE : InputKind.TEXT,
        q.header().isBlank() ? q.text() : q.header() + ": " + q.text(),
        Map.of("multiSelect", q.multiSelect(), "allowText", q.allowText()),
        choice
            ? q.options().stream().map(o -> new InputOption(o.id(), o.label())).toList()
            : List.of(),
        null,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        ttl,
        TimeoutAction.FAIL,
        null,
        null,
        "AGENT_SESSION");
  }

  /** The option ids an answer picked; empty when it is free text. */
  public static List<String> picked(final InputResponse answer) {
    if ("text".equals(answer.decision())) {
      return List.of();
    }
    if (answer.data().get("selected") instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return List.of(answer.decision());
  }

  /** Claude Code: the {@code allow} reply with {@code updatedInput.answers} (F1 of ADR 0013). */
  public static String claudeReply(
      final ObjectMapper json,
      final String requestId,
      final JsonNode input,
      final List<Question> questions,
      final List<InputResponse> answers) {
    final ObjectNode values = json.createObjectNode();
    for (int i = 0; i < questions.size(); i++) {
      final Question q = questions.get(i);
      final InputResponse a = answers.get(i);
      final List<String> ids = picked(a);
      values.put(
          q.key(),
          ids.isEmpty()
              ? String.valueOf(a.text())
              : String.join(
                  ", ",
                  q.options().stream()
                      .filter(o -> ids.contains(o.id()))
                      .map(Option::label)
                      .toList()));
    }
    final ObjectNode updated = ((ObjectNode) input.deepCopy()).set("answers", values);
    final ObjectNode response = json.createObjectNode().put("behavior", "allow");
    response.set("updatedInput", updated);
    final ObjectNode inner =
        json.createObjectNode().put("subtype", "success").put("request_id", requestId);
    inner.set("response", response);
    final ObjectNode root = json.createObjectNode().put("type", "control_response");
    root.set("response", inner);
    return root.toString();
  }

  /** ACP: the result of {@code elicitation/create}, {@code accept} with the content (F3). */
  public static JsonNode elicitationResult(
      final ObjectMapper json, final List<Question> questions, final List<InputResponse> answers) {
    final Map<String, Object> content = new LinkedHashMap<>();
    for (int i = 0; i < questions.size(); i++) {
      final Question q = questions.get(i);
      final InputResponse a = answers.get(i);
      final List<String> ids = picked(a);
      if (ids.isEmpty()) {
        content.put(q.options().isEmpty() ? q.key() : q.key() + OTHER_SUFFIX, a.text());
      } else if (q.multiSelect()) {
        final ArrayNode arr = json.createArrayNode();
        ids.forEach(arr::add);
        content.put(q.key(), arr);
      } else {
        content.put(q.key(), ids.get(0));
      }
    }
    return json.valueToTree(Map.of("action", "accept", "content", content));
  }
}
