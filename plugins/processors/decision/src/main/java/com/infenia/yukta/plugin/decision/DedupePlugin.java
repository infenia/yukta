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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code DEDUPE}: finds out whether a message means the same as one of the last few, even when the
 * wording differs. New messages leave on {@code unique}, repeats on {@code duplicate} (not
 * remembered again). The window lives for one run of the node, or with {@code persist} for the
 * execution or session, shared by the nodes that use one {@code windowKey}.
 */
@Component
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidLiteralsInIfCondition",
  "PMD.UseConcurrentHashMap"
})
public final class DedupePlugin extends DecisionPlugin {

  /** Plugin type id. */
  public static final String TYPE = "DEDUPE";

  /** Name of the question asked. */
  private static final String SAME = "same";

  /** The option that means no recent item matches. */
  private static final String NONE = "none";

  /** Characters kept of each remembered item. */
  private static final int REMEMBERED = 500;

  /** A remembered item. */
  private record Seen(String text, String identifier) {}

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param resolver resolves variable expressions in the config
   */
  public DedupePlugin(final DecisionModel model, final VariableResolver resolver) {
    super(model, resolver);
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Asks a decision model (Ollama System One or any chat model) whether a message means the"
            + " same as one of the last 'window' unique messages. Repeats leave on 'duplicate',"
            + " everything else on 'unique'. The result ({duplicate, duplicateOf, probability})"
            + " goes to the metadata key 'into' (default 'dedupe'). The window is per node and"
            + " run unless 'persist' is execution or session.",
        "window (default 10), minProbability (default 0.6), persist (none|execution|session),"
            + " windowKey.",
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    final List<ConfigField> fields = new ArrayList<>();
    fields.add(ConfigField.optional("window", ConfigField.Type.NUMBER, 10, "Unique messages kept"));
    fields.add(
        ConfigField.optional(
            "minProbability", ConfigField.Type.NUMBER, 0.6, "Probability needed to call a repeat"));
    fields.addAll(DecisionWindow.fields());
    fields.addAll(commonFields("dedupe"));
    return fields;
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return List.of("unique", "duplicate");
  }

  @Override
  protected void check(final Map<String, Object> config) {
    if (number(config, "window", 10) < 1) {
      throw new IllegalArgumentException("window must be 1 or more");
    }
    DecisionWindow.check(config);
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return Flux.deferContextual(
        context -> {
          final DecisionWindow<Seen> recent = DecisionWindow.open(TYPE, config, context);
          return input.concatMap(message -> lookup(message, config, recent));
        });
  }

  @Override
  protected Mono<Message<?>> decide(final Message<?> message, final Map<String, Object> config) {
    return lookup(message, config, new DecisionWindow<>());
  }

  private Mono<Message<?>> lookup(
      final Message<?> message,
      final Map<String, Object> config,
      final DecisionWindow<Seen> recent) {
    final String item = state(message, config);
    final Mono<Message<?>> checked;
    final List<Seen> history = recent.snapshot();
    if (history.isEmpty()) {
      checked = Mono.just(unique(message, config));
    } else {
      final List<Seen> candidates = new ArrayList<>();
      final Map<String, String> options = new LinkedHashMap<>();
      for (final Seen seen : history.reversed()) {
        candidates.add(seen);
        options.put(String.valueOf(candidates.size()), "Means the same as: " + seen.text());
      }
      options.put(NONE, "Means something different from every recent item");
      checked =
          ask(
                  message,
                  config,
                  "Current item:\n" + item,
                  Map.of(
                      SAME,
                      new Question.Choice(
                          "Does the current item mean the same as one of the recent items?",
                          options)))
              .map(
                  answers -> {
                    final Answer.Choice choice = answer(answers, SAME, Answer.Choice.class);
                    final boolean repeat =
                        !NONE.equals(choice.choice())
                            && options.containsKey(choice.choice())
                            && choice.probability() >= number(config, "minProbability", 0.6);
                    return repeat
                        ? duplicate(
                            message,
                            config,
                            candidates.get(Integer.parseInt(choice.choice()) - 1).identifier(),
                            choice.probability())
                        : unique(message, config);
                  });
    }
    return checked.doOnNext(
        output -> {
          if ("unique".equals(output.sourcePort())) {
            recent.add(
                new Seen(
                    item.length() > REMEMBERED ? item.substring(0, REMEMBERED) : item,
                    String.valueOf(message.id())),
                (int) number(config, "window", 10));
          }
        });
  }

  private static Message<?> unique(final Message<?> message, final Map<String, Object> config) {
    return emit(message, config, "dedupe", Map.of("duplicate", false), "unique");
  }

  private static Message<?> duplicate(
      final Message<?> message,
      final Map<String, Object> config,
      final String original,
      final double probability) {
    return emit(
        message,
        config,
        "dedupe",
        Map.of("duplicate", true, "duplicateOf", original, "probability", probability),
        "duplicate");
  }
}
