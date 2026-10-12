// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.util.VariableResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@code GATE}: asks a yes/no question about the message and passes it on the {@code pass} port if
 * the probability of yes reaches {@code threshold}, otherwise on {@code reject}.
 */
@Component
public final class GatePlugin extends DecisionPlugin {

  /** Plugin type id. */
  public static final String TYPE = "GATE";

  /** Config key: the question. */
  private static final String QUESTION = "question";

  /** Name of the question asked. */
  private static final String GATE = "gate";

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param resolver resolves variable expressions in the config
   */
  public GatePlugin(final DecisionModel model, final VariableResolver resolver) {
    super(model, resolver);
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Asks a yes/no question about the message with a decision model (Ollama System One or any"
            + " chat model). Yes at or above the threshold leaves on 'pass', otherwise on"
            + " 'reject'. The probability of yes goes to the metadata key 'into' (default"
            + " 'gate').",
        "question: e.g. 'Is this message safe to send to a customer?', threshold (default 0.5).",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    final List<ConfigField> fields = new ArrayList<>();
    fields.add(ConfigField.required(QUESTION, ConfigField.Type.STRING, "A yes/no question"));
    fields.add(
        ConfigField.optional(
            "threshold", ConfigField.Type.NUMBER, 0.5, "Probability of yes needed to pass"));
    fields.addAll(commonFields(GATE));
    return fields;
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return List.of("pass", "reject");
  }

  @Override
  protected void check(final Map<String, Object> config) {
    require(config, QUESTION);
  }

  @Override
  protected Mono<Message<?>> decide(final Message<?> message, final Map<String, Object> config) {
    return ask(
            message,
            config,
            state(message, config),
            Map.of(GATE, new Question.YesNo(string(config, QUESTION, ""))))
        .map(
            answers -> {
              final double yes = answer(answers, GATE, Answer.YesNo.class).probability();
              final boolean passed = yes >= number(config, "threshold", 0.5);
              return emit(
                  message,
                  config,
                  GATE,
                  Map.of("probability", yes, "passed", passed),
                  passed ? "pass" : "reject");
            });
  }
}
