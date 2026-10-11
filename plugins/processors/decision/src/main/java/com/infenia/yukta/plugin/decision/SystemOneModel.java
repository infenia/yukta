// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks Ollama's System One endpoint (<a href="https://docs.ollama.com/api/systemone">{@code POST
 * /v1/systemone}</a>). The probabilities are the model's own and calibrated, and one call answers
 * all questions.
 */
@Component
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class SystemOneModel implements DecisionModel {

  /** The endpoint, below the base URL. */
  private static final String PATH = "/v1/systemone";

  /** Shared JSON mapper. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** HTTP client. */
  private final WebClient client;

  /**
   * Creates the model.
   *
   * @param builder the web client builder
   */
  public SystemOneModel(final WebClient.Builder builder) {
    this.client = builder.build();
  }

  @Override
  public Mono<Map<String, Answer>> answer(
      final ModelSpec spec, final String state, final Map<String, Question> questions) {
    return client
        .post()
        .uri(spec.baseUrl() + PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(JSON.writeValueAsString(request(spec.model(), state, questions)))
        .retrieve()
        .bodyToMono(String.class)
        .timeout(spec.timeout())
        .map(body -> parse(JSON.readTree(body), questions));
  }

  private static Map<String, Object> request(
      final String model, final String state, final Map<String, Question> questions) {
    final Map<String, Object> asked = new LinkedHashMap<>();
    questions.forEach((name, question) -> asked.put(name, ask(question)));
    return Map.of("model", model, "state", state, "questions", asked);
  }

  private static Map<String, Object> ask(final Question question) {
    return switch (question) {
      case Question.Choice choice ->
          Map.of(
              "type", "choice",
              "instructions", choice.instructions(),
              "criteria", choice.criteria());
      case Question.YesNo yesNo -> Map.of("type", "noul", "instructions", yesNo.instructions());
      case Question.Score score ->
          Map.of(
              "type", "score",
              "instructions", score.instructions(),
              "criteria", score.levels());
    };
  }

  private static Map<String, Answer> parse(
      final JsonNode reply, final Map<String, Question> questions) {
    final Map<String, Answer> answers = new LinkedHashMap<>();
    questions.forEach(
        (name, question) -> {
          final JsonNode node = reply.path("answers").path(name);
          if (node.isMissingNode()) {
            throw new IllegalStateException("System One gave no answer to '" + name + "'");
          }
          answers.put(name, read(question, node));
        });
    return answers;
  }

  private static Answer read(final Question question, final JsonNode node) {
    return switch (question) {
      case Question.Choice _ -> {
        final Map<String, Double> probabilities = new LinkedHashMap<>();
        node.path("probabilities")
            .properties()
            .forEach(entry -> probabilities.put(entry.getKey(), entry.getValue().asDouble(0)));
        yield new Answer.Choice(node.path("choice").asString(""), probabilities);
      }
      case Question.YesNo _ -> new Answer.YesNo(node.path("noul").asDouble(0));
      case Question.Score _ -> new Answer.Score(node.path("score").asDouble(0));
    };
  }
}
