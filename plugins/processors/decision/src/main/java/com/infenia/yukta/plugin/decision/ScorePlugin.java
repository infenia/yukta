// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@code SCORE}: rates the message on an ordered scale. With a {@code threshold} it routes to
 * {@code high} or {@code low}.
 */
@Component
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class ScorePlugin extends DecisionPlugin {

  /** Plugin type id. */
  public static final String TYPE = "SCORE";

  /** Config key: the question. */
  private static final String QUESTION = "question";

  /** Config key: the levels. */
  private static final String LEVELS = "levels";

  /** Config key: the routing threshold. */
  private static final String THRESHOLD = "threshold";

  /** Name of the question asked. */
  private static final String SCORE = "score";

  /** The most levels System One accepts. */
  private static final int MAX_LEVELS = 26;

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param resolver resolves variable expressions in the config
   */
  public ScorePlugin(final DecisionModel model, final VariableResolver resolver) {
    super(model, resolver);
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Rates the message on an ordered scale with a decision model (Ollama System One or any"
            + " chat model). The score (0 to levels-1, probability weighted) and the nearest level"
            + " go to the metadata key 'into' (default 'score'). With a threshold the message"
            + " leaves on 'high' (score at or above it) or 'low'.",
        "question, levels: [lowest, ..., highest] (2 to 26), threshold (optional).",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    final List<ConfigField> fields = new ArrayList<>();
    fields.add(ConfigField.required(QUESTION, ConfigField.Type.STRING, "What is rated"));
    fields.add(ConfigField.required(LEVELS, ConfigField.Type.LIST, "Levels, lowest first"));
    fields.add(
        ConfigField.optional(
            THRESHOLD, ConfigField.Type.NUMBER, null, "Score from which a message is 'high'"));
    fields.addAll(commonFields(SCORE));
    return fields;
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return config.get(THRESHOLD) == null ? List.of(PORT_DEFAULT) : List.of("high", "low");
  }

  @Override
  protected void check(final Map<String, Object> config) {
    require(config, QUESTION);
    final int levels = levels(config).size();
    if (levels < 2 || levels > MAX_LEVELS) {
      throw new IllegalArgumentException("levels needs 2 to " + MAX_LEVELS + " entries");
    }
  }

  @Override
  protected Mono<Message<?>> decide(final Message<?> message, final Map<String, Object> config) {
    final List<String> levels = levels(config);
    return ask(
            message,
            config,
            state(message, config),
            Map.of(SCORE, new Question.Score(string(config, QUESTION, ""), levels)))
        .map(
            answers -> {
              final double score = answer(answers, SCORE, Answer.Score.class).score();
              final int nearest = (int) Math.max(0, Math.min(levels.size() - 1, Math.round(score)));
              final String port =
                  config.get(THRESHOLD) == null
                      ? PORT_DEFAULT
                      : score >= number(config, THRESHOLD, 0) ? "high" : "low";
              return emit(
                  message, config, SCORE, Map.of(SCORE, score, "level", levels.get(nearest)), port);
            });
  }

  private static List<String> levels(final Map<String, Object> config) {
    return config.get(LEVELS) instanceof final List<?> list
        ? list.stream().map(String::valueOf).toList()
        : List.of();
  }
}
