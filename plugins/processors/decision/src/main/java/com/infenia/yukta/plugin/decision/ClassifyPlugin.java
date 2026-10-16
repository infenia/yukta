// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@code CLASSIFY}: puts the message into one of the configured labels, and can route by label.
 * Answers below {@code minProbability} go to the {@code uncertain} port.
 */
@Component
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.AvoidLiteralsInIfCondition",
  "PMD.ConfusingTernary",
  "PMD.UseConcurrentHashMap"
})
public final class ClassifyPlugin extends DecisionPlugin {

  /** Plugin type id. */
  public static final String TYPE = "CLASSIFY";

  /** Config key: the labels. */
  private static final String LABELS = "labels";

  /** Config key: route by label. */
  private static final String ROUTE_BY_LABEL = "routeByLabel";

  /** Name of the question asked. */
  private static final String QUESTION = "label";

  /** The port of answers the model is not sure about. */
  private static final String PORT_UNCERTAIN = "uncertain";

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param resolver resolves variable expressions in the config
   */
  public ClassifyPlugin(final DecisionModel model, final VariableResolver resolver) {
    super(model, resolver);
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Classifies the message with a decision model (Ollama System One or any chat model) into"
            + " one of the labels. The label, its probability and all probabilities go to the"
            + " metadata key 'into' (default 'classification'). With routeByLabel the message"
            + " leaves on the port named like its label, or 'uncertain' below minProbability.",
        "labels: {label: when it applies}, question, minProbability (default 0.6), routeByLabel."
            + " Describe each label well; a small model needs it.",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    final List<ConfigField> fields = new ArrayList<>();
    fields.add(
        ConfigField.required(
            LABELS, ConfigField.Type.MAP, "Label to a description of when it applies"));
    fields.add(
        ConfigField.optional(
            "question", ConfigField.Type.STRING, "Which label fits this?", "What is asked"));
    fields.add(
        ConfigField.optional(
            "minProbability", ConfigField.Type.NUMBER, 0.6, "Below this the answer is uncertain"));
    fields.add(
        ConfigField.optional(
            ROUTE_BY_LABEL, ConfigField.Type.BOOLEAN, false, "Emit on a port per label"));
    fields.addAll(commonFields("classification"));
    return fields;
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final List<String> ports = new ArrayList<>();
    if (routeByLabel(config)) {
      ports.addAll(labels(config).keySet());
      ports.add(PORT_UNCERTAIN);
    } else {
      ports.add(PORT_DEFAULT);
    }
    return ports;
  }

  @Override
  protected void check(final Map<String, Object> config) {
    if (labels(config).size() < 2) {
      throw new IllegalArgumentException("labels needs at least two labels");
    }
  }

  @Override
  protected Mono<Message<?>> decide(final Message<?> message, final Map<String, Object> config) {
    final Map<String, String> labels = labels(config);
    final Question question =
        new Question.Choice(string(config, "question", "Which label fits this?"), labels);
    return ask(message, config, state(message, config), Map.of(QUESTION, question))
        .map(
            answers -> {
              final Answer.Choice choice = answer(answers, QUESTION, Answer.Choice.class);
              final boolean uncertain =
                  !labels.containsKey(choice.choice())
                      || choice.probability() < number(config, "minProbability", 0.6);
              final Map<String, Object> result = new LinkedHashMap<>(choice.toMap());
              result.put("label", choice.choice());
              result.put(PORT_UNCERTAIN, uncertain);
              final String port =
                  !routeByLabel(config)
                      ? PORT_DEFAULT
                      : uncertain ? PORT_UNCERTAIN : choice.choice();
              return emit(message, config, "classification", result, port);
            });
  }

  private static boolean routeByLabel(final Map<String, Object> config) {
    return Boolean.parseBoolean(string(config, ROUTE_BY_LABEL, "false"));
  }

  private static Map<String, String> labels(final Map<String, Object> config) {
    final Map<String, String> labels = new LinkedHashMap<>();
    map(config, LABELS).forEach((label, meaning) -> labels.put(label, String.valueOf(meaning)));
    return labels;
  }
}
