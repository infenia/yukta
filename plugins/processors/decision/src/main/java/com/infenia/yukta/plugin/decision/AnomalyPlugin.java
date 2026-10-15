// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code ANOMALY}: compares each message with the last few and asks whether it is unusual. Messages
 * leave on {@code normal} or {@code anomalous}. The first {@code minHistory} messages are normal.
 * The window lives for one run of the node.
 */
@Component
@SuppressWarnings("PMD.InsufficientStringBufferDeclaration")
public final class AnomalyPlugin extends DecisionPlugin {

  /** Plugin type id. */
  public static final String TYPE = "ANOMALY";

  /** Name of the question asked. */
  private static final String ANOMALY = "anomaly";

  /** Characters kept of each remembered item. */
  private static final int REMEMBERED = 500;

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param resolver resolves variable expressions in the config
   */
  public AnomalyPlugin(final DecisionModel model, final VariableResolver resolver) {
    super(model, resolver);
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Asks a decision model (Ollama System One or any chat model) whether each message is"
            + " unusual compared with the last 'window' messages. Leaves on 'anomalous' at or above"
            + " the threshold, otherwise on 'normal'; the first 'minHistory' messages are normal."
            + " The probability goes to the metadata key 'into' (default 'anomaly'). The window is"
            + " per node and run.",
        "window (default 20), minHistory (default 5), threshold (default 0.5), question.",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    final List<ConfigField> fields = new ArrayList<>();
    fields.add(ConfigField.optional("window", ConfigField.Type.NUMBER, 20, "Recent messages kept"));
    fields.add(
        ConfigField.optional(
            "minHistory", ConfigField.Type.NUMBER, 5, "Messages seen before judging starts"));
    fields.add(
        ConfigField.optional(
            "threshold",
            ConfigField.Type.NUMBER,
            0.5,
            "Probability from which a message is anomalous"));
    fields.add(
        ConfigField.optional(
            "question",
            ConfigField.Type.STRING,
            "Is the current item unusual compared with the recent items?",
            "What is asked"));
    fields.addAll(commonFields(ANOMALY));
    return fields;
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return List.of("normal", "anomalous");
  }

  @Override
  protected void check(final Map<String, Object> config) {
    if (number(config, "window", 20) < 1 || number(config, "minHistory", 5) < 0) {
      throw new IllegalArgumentException("window must be 1 or more and minHistory 0 or more");
    }
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return Flux.defer(
        () -> {
          final Deque<String> recent = new ArrayDeque<>();
          return input.concatMap(message -> judge(message, config, recent));
        });
  }

  @Override
  protected Mono<Message<?>> decide(final Message<?> message, final Map<String, Object> config) {
    return judge(message, config, new ArrayDeque<>());
  }

  private Mono<Message<?>> judge(
      final Message<?> message, final Map<String, Object> config, final Deque<String> recent) {
    final String item = state(message, config);
    final Mono<Message<?>> judged;
    if (recent.size() < number(config, "minHistory", 5)) {
      judged =
          Mono.just(
              emit(message, config, ANOMALY, Map.of("probability", 0.0, "warmup", true), "normal"));
    } else {
      final StringBuilder state = new StringBuilder("Recent items, oldest first:\n");
      recent.forEach(seen -> state.append("- ").append(seen).append('\n'));
      state.append("\nCurrent item:\n").append(item);
      judged =
          ask(
                  message,
                  config,
                  state.toString(),
                  Map.of(
                      ANOMALY,
                      new Question.YesNo(
                          string(
                              config,
                              "question",
                              "Is the current item unusual compared with the recent items?"))))
              .map(
                  answers -> {
                    final double yes = answer(answers, ANOMALY, Answer.YesNo.class).probability();
                    final boolean anomalous = yes >= number(config, "threshold", 0.5);
                    return emit(
                        message,
                        config,
                        ANOMALY,
                        Map.of("probability", yes, "warmup", false),
                        anomalous ? "anomalous" : "normal");
                  });
    }
    return judged.doOnNext(
        _ -> {
          recent.addLast(item.length() > REMEMBERED ? item.substring(0, REMEMBERED) : item);
          while (recent.size() > number(config, "window", 20)) {
            recent.removeFirst();
          }
        });
  }
}
