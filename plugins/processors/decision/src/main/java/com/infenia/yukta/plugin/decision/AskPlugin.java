// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.util.VariableResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@code ASK}: asks several questions about the message in one model call and puts all answers in
 * the metadata, for a BRANCH or FILTER downstream to act on.
 */
@Component
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CyclomaticComplexity",
  "PMD.UseConcurrentHashMap"
})
public final class AskPlugin extends DecisionPlugin {

  /** Plugin type id. */
  public static final String TYPE = "ASK";

  /** Config key: the questions. */
  private static final String QUESTIONS = "questions";

  /** The most questions System One accepts in one request. */
  private static final int MAX_QUESTIONS = 64;

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param resolver resolves variable expressions in the config
   */
  public AskPlugin(final DecisionModel model, final VariableResolver resolver) {
    super(model, resolver);
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Asks up to 64 questions about the message in one call to a decision model (Ollama System"
            + " One or any chat model). The answers, by question name, go to the metadata key"
            + " 'into' (default 'answers'); route on them with BRANCH.",
        "questions: {name: {type: choice|yesno|score, instructions, criteria}}. criteria is a"
            + " map {option: description} for choice and a list of levels for score.",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    final List<ConfigField> fields = new ArrayList<>();
    fields.add(
        ConfigField.required(QUESTIONS, ConfigField.Type.MAP, "Question name to its definition"));
    fields.addAll(commonFields("answers"));
    return fields;
  }

  @Override
  protected void check(final Map<String, Object> config) {
    if (questions(config).size() > MAX_QUESTIONS) {
      throw new IllegalArgumentException("At most " + MAX_QUESTIONS + " questions");
    }
  }

  @Override
  protected Mono<Message<?>> decide(final Message<?> message, final Map<String, Object> config) {
    return ask(message, config, state(message, config), questions(config))
        .map(
            answers -> {
              final Map<String, Object> result = new LinkedHashMap<>();
              answers.forEach((name, answer) -> result.put(name, answer.toMap()));
              return emit(message, config, "answers", result, PORT_DEFAULT);
            });
  }

  private static Map<String, Question> questions(final Map<String, Object> config) {
    final Map<String, Question> questions = new LinkedHashMap<>();
    map(config, QUESTIONS)
        .forEach(
            (name, definition) -> {
              if (!(definition instanceof final Map<?, ?> fields)) {
                throw new IllegalArgumentException("Question '" + name + "' must be a map");
              }
              questions.put(name, question(name, fields));
            });
    return questions;
  }

  private static Question question(final String name, final Map<?, ?> fields) {
    final String instructions = String.valueOf(fields.get("instructions"));
    final Object criteria = fields.get("criteria");
    final String type = String.valueOf(fields.get("type")).toLowerCase(java.util.Locale.ROOT);
    return switch (type) {
      case "choice" -> {
        if (!(criteria instanceof final Map<?, ?> options) || options.size() < 2) {
          throw new IllegalArgumentException("Question '" + name + "' needs two or more criteria");
        }
        final Map<String, String> asText = new LinkedHashMap<>();
        options.forEach((key, meaning) -> asText.put(String.valueOf(key), String.valueOf(meaning)));
        yield new Question.Choice(instructions, asText);
      }
      case "yesno" -> new Question.YesNo(instructions);
      case "score" -> {
        if (!(criteria instanceof final List<?> levels) || levels.size() < 2) {
          throw new IllegalArgumentException("Question '" + name + "' needs two or more levels");
        }
        yield new Question.Score(instructions, levels.stream().map(String::valueOf).toList());
      }
      default ->
          throw new IllegalArgumentException(
              "Question '" + name + "' has unknown type '" + type + "' (choice, yesno or score)");
    };
  }
}
