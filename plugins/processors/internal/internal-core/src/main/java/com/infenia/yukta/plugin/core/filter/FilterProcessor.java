// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.filter;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.core.util.SimpleExpressionEvaluator;
import com.infenia.yukta.plugin.exception.FilterEvaluationException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.SpelUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Evaluates a boolean predicate against a message. If true, the message passes through; if false,
 * it is dropped or rerouted.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.AvoidCatchingGenericException",
  "PMD.TooManyMethods",
  "PMD.GuardLogStatement"
})
public final class FilterProcessor implements ProcessorPlugin {

  /** Type. */
  private static final String TYPE = "FILTER";

  /** Engine spel. */
  private static final String ENGINE_SPEL = "SpEL";

  /** Engine simple. */
  private static final String ENGINE_SIMPLE = "SIMPLE";

  /** Engine rego. */
  private static final String ENGINE_REGO = "REGO";

  /** Config condition. */
  private static final String CONFIG_CONDITION = "condition";

  /** Config engine. */
  private static final String CONFIG_ENGINE = "engine";

  /** Config key naming the expression engine without clashing with the node policy. */
  private static final String CONFIG_LANGUAGE = "language";

  /** Config strict. */
  private static final String CONFIG_STRICT = "strictMode";

  /** Discard port. */
  private static final String DISCARD_PORT = "discardPort";

  /** Config allow after heavy. */
  private static final String CONFIG_ALLOW_AFTER_HEAVY = "allowAfterHeavy";

  /** Reporter. */
  private final PluginMetricsReporter reporter;

  /**
   * Constructs a new FilterProcessor with an optional metrics reporter.
   *
   * @param reporterProvider the provider for PluginMetricsReporter
   */
  public FilterProcessor(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this.reporter = reporterProvider.getIfAvailable(() -> (nodeId, metric, tag) -> {});
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Evaluates a boolean predicate against a message. If true, the message passes through; "
            + "if false, it is dropped or rerouted.",
        "Configure with:\n"
            + "- condition: The boolean expression to evaluate.\n"
            + "- engine: 'SpEL' (default) or 'SIMPLE'.\n"
            + "- strictMode: Boolean. If true (default), throws exception on evaluation error.\n"
            + "- discardPort: Optional port name to route messages that do not match the"
            + " condition.\n"
            + "- allowAfterHeavy: Boolean. If true, allows this filter to be placed after heavy"
            + " computation without validation warnings (default: false).",
        Optional.of(new UiDesign("blue", "filter_alt", "Filter", 140, 80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required("condition", ConfigField.Type.EXPRESSION, "Keep messages where true"),
        ConfigField.choice(
            "engine", false, ENGINE_SPEL, "Expression engine", ENGINE_SPEL, ENGINE_SIMPLE),
        ConfigField.choice(
            "language",
            false,
            null,
            "Expression engine, for a node that also sets the engine policy object",
            ENGINE_SPEL,
            ENGINE_SIMPLE),
        ConfigField.optional("strictMode", ConfigField.Type.BOOLEAN, true, "Fail on eval errors"),
        ConfigField.optional(
            "discardPort", ConfigField.Type.STRING, null, "Port for rejected messages"));
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final List<String> ports = new ArrayList<>();
    ports.add("default");
    final String discardPort = (String) config.get(DISCARD_PORT);
    if (discardPort != null && !discardPort.isBlank()) {
      ports.add(discardPort);
    }
    return ports;
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    final String engine = expressionEngine(config);

    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory for Filter plugin"));
    }

    if (ENGINE_SPEL.equalsIgnoreCase(engine)) {
      SpelUtils.preParse(condition);
    } else if (ENGINE_SIMPLE.equalsIgnoreCase(engine)) {
      SimpleExpressionEvaluator.preParse(condition);
    }

    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    final String engine = expressionEngine(config);
    final boolean strictMode = (Boolean) config.getOrDefault(CONFIG_STRICT, true);
    final String discardPort = (String) config.get(DISCARD_PORT);

    return input.flatMap(
        message ->
            Mono.deferContextual(
                ctx -> {
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  return executeFilter(message, nodeId, condition, engine, strictMode, discardPort);
                }));
  }

  private Mono<Message<?>> executeFilter(
      final Message<?> message,
      final String nodeId,
      final String condition,
      final String engine,
      final boolean strictMode,
      final String discardPort) {
    try {
      final boolean isMatch = evaluate(condition, engine, message);
      return handleMatchResult(message, nodeId, isMatch, discardPort);
    } catch (final Exception e) {
      return handleEvaluationError(nodeId, condition, strictMode, e);
    }
  }

  private Mono<Message<?>> handleMatchResult(
      final Message<?> message,
      final String nodeId,
      final boolean isMatch,
      final String discardPort) {
    if (isMatch) {
      reporter.increment(nodeId, "filter", "MATCH");
      return Mono.just(message.withSourcePort("default"));
    } else {
      reporter.increment(nodeId, "filter", "DISCARD");
      if (discardPort != null && !discardPort.isBlank()) {
        return Mono.just(message.withSourcePort(discardPort));
      }
    }
    return Mono.empty();
  }

  private Mono<Message<?>> handleEvaluationError(
      final String nodeId, final String condition, final boolean strictMode, final Exception err) {
    reporter.increment(nodeId, "filter", "ERROR");
    log.error("Filter evaluation failed for condition [{}]: {}", condition, err.getMessage());
    if (!strictMode) {
      return Mono.empty();
    }
    throw new FilterEvaluationException(
        "Filter evaluation failed for condition: " + condition, err);
  }

  private boolean evaluate(final String condition, final String engine, final Message<?> message) {
    if (ENGINE_SPEL.equalsIgnoreCase(engine)) {
      final Map<String, Object> variables =
          Map.of(
              "payload", message.payload(),
              "metadata", message.metadata());
      final Boolean result = SpelUtils.evaluateSync(condition, message, variables);
      return result != null && result;
    } else if (ENGINE_SIMPLE.equalsIgnoreCase(engine)) {
      return SimpleExpressionEvaluator.evaluate(condition, message);
    }
    throw new IllegalArgumentException("Unsupported engine: " + engine);
  }

  /**
   * The expression engine: {@code language}, else {@code engine} when it is text. An {@code engine}
   * object is the node's failure policy, not this option.
   */
  private static String expressionEngine(final Map<String, Object> config) {
    for (final String key : new String[] {CONFIG_LANGUAGE, CONFIG_ENGINE}) {
      if (config.get(key) instanceof final String text) {
        return text;
      }
    }
    return ENGINE_SPEL;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory"));
    }
    final String engine = expressionEngine(config);
    if (!ENGINE_SPEL.equalsIgnoreCase(engine)
        && !ENGINE_SIMPLE.equalsIgnoreCase(engine)
        && !ENGINE_REGO.equalsIgnoreCase(engine)) {
      return Mono.error(new IllegalArgumentException("Unsupported engine: " + engine));
    }
    if (ENGINE_REGO.equalsIgnoreCase(engine)) {
      return Mono.error(
          new IllegalArgumentException(
              "REGO engine is reserved for future use and not yet implemented."));
    }
    return Mono.empty();
  }

  @Override
  public boolean suppressOptimizationHint(final Map<String, Object> config) {
    final Object allowAfterHeavy = config.get(CONFIG_ALLOW_AFTER_HEAVY);
    return Boolean.TRUE.equals(allowAfterHeavy);
  }

  /**
   * Validates filter placement in the workflow context. Issues an advisory warning if the filter is
   * positioned after heavy computation without explicit allowance.
   */
  @Override
  public Mono<Void> validateInContext(
      final WorkflowContext context, final Map<String, Object> config) {
    // If filter explicitly allows placement after heavy computation, no validation needed
    if (suppressOptimizationHint(config)) {
      return Mono.empty();
    }

    // Check if there are incoming edges (filter has ancestors)
    if (!context.incomingEdges().isEmpty()) {
      log.atWarn()
          .log(
              "Performance Hint: Filter [{}] is positioned after heavy computation. "
                  + "Moving it closer to the Trigger may reduce unnecessary load. "
                  + "Set allowAfterHeavy=true in filter config to suppress this warning.",
              context.nodeId());
    }
    return Mono.empty();
  }
}
