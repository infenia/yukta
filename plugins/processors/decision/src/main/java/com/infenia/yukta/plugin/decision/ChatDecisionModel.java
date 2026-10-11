// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.plugin.llm.ChatTurn;
import com.infenia.yukta.plugin.llm.LlmGateway;
import com.infenia.yukta.plugin.llm.LlmRequest;
import com.infenia.yukta.plugin.llm.Provider;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks any chat model (OpenAI-compatible, Anthropic, Ollama) to answer the questions as JSON.
 *
 * <p>A chat model reports no probabilities, so the one it states for its answer is an estimate and
 * not calibrated; the other options share what is left. Prefer {@link SystemOneModel} where
 * probabilities decide something.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.InsufficientStringBufferDeclaration", "PMD.UseConcurrentHashMap"})
public final class ChatDecisionModel implements DecisionModel {

  /** The instructions that make the model answer in a fixed shape. */
  private static final String SYSTEM =
      "You are a decision component in a workflow engine. Answer every question about the state"
          + " given. Reply with one JSON object and nothing else: no prose, no code fences.";

  /** Shared JSON mapper. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Calls the chat models. */
  private final LlmGateway gateway;

  @Override
  public Mono<Map<String, Answer>> answer(
      final ModelSpec spec, final String state, final Map<String, Question> questions) {
    return Mono.fromCallable(
            () ->
                gateway.call(
                    new LlmRequest(
                        Provider.from(spec.provider()),
                        spec.baseUrl(),
                        spec.apiKey(),
                        spec.model(),
                        0.0,
                        null,
                        spec.timeout(),
                        SYSTEM,
                        java.util.List.of(new ChatTurn("user", prompt(state, questions))))))
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(spec.timeout().plusSeconds(1))
        .map(response -> parse(response.text(), questions));
  }

  private static String prompt(final String state, final Map<String, Question> questions) {
    final StringBuilder text =
        new StringBuilder("State:\n").append(state).append("\n\nQuestions:\n");
    questions.forEach((name, question) -> text.append(describe(name, question)));
    text.append(
        "\nReply as {\"<question>\": <answer>, ...}. An answer is {\"choice\": \"<key>\","
            + " \"probability\": <0 to 1, how sure you are>} for a choice, {\"probability\":"
            + " <0 to 1, that the answer is yes>} for a yes/no question and {\"score\": <number"
            + " from 0 to the highest level index>} for a score.");
    return text.toString();
  }

  private static String describe(final String name, final Question question) {
    final StringBuilder text =
        new StringBuilder("- ").append(name).append(": ").append(question.instructions());
    switch (question) {
      case Question.Choice choice -> {
        text.append(" (choice, options:");
        choice
            .criteria()
            .forEach(
                (key, meaning) -> text.append("\n    ").append(key).append(": ").append(meaning));
        text.append(')');
      }
      case Question.YesNo _ -> text.append(" (yes/no)");
      case Question.Score score -> {
        text.append(" (score, levels:");
        for (int i = 0; i < score.levels().size(); i++) {
          text.append("\n    ").append(i).append(": ").append(score.levels().get(i));
        }
        text.append(')');
      }
    }
    return text.append('\n').toString();
  }

  private static Map<String, Answer> parse(
      final String text, final Map<String, Question> questions) {
    final int start = text.indexOf('{');
    final int end = text.lastIndexOf('}');
    if (start < 0 || end < start) {
      throw new IllegalStateException("The model did not answer with JSON");
    }
    final JsonNode reply = JSON.readTree(text.substring(start, end + 1));
    final Map<String, Answer> answers = new LinkedHashMap<>();
    questions.forEach(
        (name, question) -> {
          final JsonNode node = reply.path(name);
          if (node.isMissingNode()) {
            throw new IllegalStateException("The model gave no answer to '" + name + "'");
          }
          answers.put(name, read(name, question, node));
        });
    return answers;
  }

  private static Answer read(final String name, final Question question, final JsonNode node) {
    return switch (question) {
      case Question.Choice choice -> choice(name, choice, node);
      case Question.YesNo _ -> new Answer.YesNo(clamp(node.path("probability").asDouble(0), 1));
      case Question.Score score ->
          new Answer.Score(clamp(node.path("score").asDouble(0), score.levels().size() - 1));
    };
  }

  private static Answer choice(
      final String name, final Question.Choice question, final JsonNode node) {
    final String chosen = node.path("choice").asString("");
    if (!question.criteria().containsKey(chosen)) {
      throw new IllegalStateException(
          "The model chose '" + chosen + "' for '" + name + "', which is not an option");
    }
    final double sure = clamp(node.path("probability").asDouble(0), 1);
    final double others =
        question.criteria().size() > 1 ? (1 - sure) / (question.criteria().size() - 1) : 0;
    final Map<String, Double> probabilities = new LinkedHashMap<>();
    question
        .criteria()
        .keySet()
        .forEach(key -> probabilities.put(key, key.equals(chosen) ? sure : others));
    return new Answer.Choice(chosen, probabilities);
  }

  private static double clamp(final double value, final double max) {
    return Math.max(0, Math.min(max, value));
  }
}
