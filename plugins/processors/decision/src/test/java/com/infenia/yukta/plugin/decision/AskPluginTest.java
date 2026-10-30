// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for AskPlugin. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class AskPluginTest {

  private static Map<String, Object> config(final Object questions) {
    final Map<String, Object> config = new HashMap<>();
    config.put("model", "nimble");
    config.put("questions", questions);
    return config;
  }

  private static Map<String, Object> all() {
    final Map<String, Object> questions = new LinkedHashMap<>();
    questions.put(
        "topic",
        Map.of("type", "choice", "instructions", "Topic?", "criteria", Map.of("a", "x", "b", "y")));
    questions.put("urgent", Map.of("type", "YesNo", "instructions", "Urgent?"));
    questions.put(
        "severity",
        Map.of("type", "score", "instructions", "How bad?", "criteria", List.of("low", "high")));
    return questions;
  }

  private static AskPlugin plugin(final FakeModel model) {
    return new AskPlugin(model, FakeModel.plainResolver());
  }

  @Test
  void process_asksAllQuestionsInOneCallAndStoresAnswers() {
    final FakeModel model =
        FakeModel.always(
            Map.of(
                "topic", new Answer.Choice("a", Map.of("a", 0.9)),
                "urgent", new Answer.YesNo(0.2),
                "severity", new Answer.Score(1.5)));

    final Message<?> out =
        plugin(model).process(Flux.just(FakeModel.message("text")), config(all())).blockFirst();

    assertThat(model.asked).hasSize(1);
    assertThat(model.asked.getFirst().questions())
        .containsOnlyKeys("topic", "urgent", "severity")
        .containsEntry("urgent", new Question.YesNo("Urgent?"))
        .containsEntry("severity", new Question.Score("How bad?", List.of("low", "high")));
    assertThat(out.sourcePort()).isEqualTo("default");
    assertThat(out.metadata().get("answers"))
        .isEqualTo(
            Map.of(
                "topic",
                Map.of("choice", "a", "probability", 0.9, "probabilities", Map.of("a", 0.9)),
                "urgent",
                Map.of("probability", 0.2),
                "severity",
                Map.of("score", 1.5)));
  }

  @Test
  void validateConfig_acceptsValidQuestions() {
    final AskPlugin plugin = plugin(FakeModel.always(Map.of()));

    assertThat(plugin.getType()).isEqualTo("ASK");
    assertThat(plugin.describe().description()).contains("up to 64 questions");
    assertThat(
            plugin.getConfigSchema().stream().filter(ConfigField::required).map(ConfigField::name))
        .containsExactly("questions", "model");
    StepVerifier.create(plugin.validateConfig(config(all()))).verifyComplete();
  }

  @Test
  void validateConfig_rejectsBadQuestions() {
    final AskPlugin plugin = plugin(FakeModel.always(Map.of()));

    StepVerifier.create(plugin.validateConfig(config("text")))
        .expectErrorMessage("questions must be a non-empty map")
        .verify();
    StepVerifier.create(plugin.validateConfig(config(Map.of("q", "text"))))
        .expectErrorMessage("Question 'q' must be a map")
        .verify();
    StepVerifier.create(plugin.validateConfig(config(Map.of("q", Map.of("type", "essay")))))
        .expectErrorMessage("Question 'q' has unknown type 'essay' (choice, yesno or score)")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(
                config(Map.of("q", Map.of("type", "choice", "criteria", Map.of("only", "one"))))))
        .expectErrorMessage("Question 'q' needs two or more criteria")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(config(Map.of("q", Map.of("type", "choice", "criteria", "x")))))
        .expectErrorMessage("Question 'q' needs two or more criteria")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(
                config(Map.of("q", Map.of("type", "score", "criteria", List.of("one"))))))
        .expectErrorMessage("Question 'q' needs two or more levels")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(config(Map.of("q", Map.of("type", "score", "criteria", "x")))))
        .expectErrorMessage("Question 'q' needs two or more levels")
        .verify();
  }

  @Test
  void validateConfig_rejectsMoreThan64Questions() {
    final Map<String, Object> many = new LinkedHashMap<>();
    IntStream.range(0, 65).forEach(i -> many.put("q" + i, Map.of("type", "yesno")));

    StepVerifier.create(plugin(FakeModel.always(Map.of())).validateConfig(config(many)))
        .expectErrorMessage("At most 64 questions")
        .verify();
  }
}
