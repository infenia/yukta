/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.plugin.core;

import com.infenia.jagratha.plugin.FilterEvaluationException;
import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.PluginMetricsReporter;
import com.infenia.jagratha.plugin.ProcessorPlugin;
import com.infenia.jagratha.util.SpelUtils;
import java.util.Map;
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
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidCatchingGenericException"})
public final class FilterProcessor implements ProcessorPlugin {

  private static final String TYPE = "filter";
  private static final String ENGINE_SPEL = "SpEL";
  private static final String ENGINE_SIMPLE = "SIMPLE";
  private static final String ENGINE_REGO = "REGO";

  private static final String CONFIG_CONDITION = "condition";
  private static final String CONFIG_ENGINE = "engine";
  private static final String CONFIG_STRICT = "strictMode";
  private static final String DISCARD_PORT = "discardPort";

  private final PluginMetricsReporter reporter;

  /**
   * Constructs a new FilterProcessor with an optional metrics reporter.
   *
   * @param reporterProvider the provider for PluginMetricsReporter
   */
  public FilterProcessor(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this.reporter = reporterProvider.getIfAvailable(() -> (nodeId, status) -> {});
  }

  @Override
  public String getDescription() {
    return "Evaluates a boolean predicate against a message. If true, the message passes through; "
        + "if false, it is dropped or rerouted.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- condition: The boolean expression to evaluate.\n"
        + "- engine: 'SpEL' (default) or 'SIMPLE'.\n"
        + "- strictMode: Boolean. If true (default), throws exception on evaluation error.\n"
        + "- discardPort: Optional port name to route messages that do not match the condition.";
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    final String engine = (String) config.getOrDefault(CONFIG_ENGINE, ENGINE_SPEL);

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
  public Flux<Message> process(final Flux<Message> input, final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    final String engine = (String) config.getOrDefault(CONFIG_ENGINE, ENGINE_SPEL);
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

  private Mono<Message> executeFilter(
      final Message message,
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

  private Mono<Message> handleMatchResult(
      final Message message, final String nodeId, final boolean isMatch, final String discardPort) {
    Mono<Message> result = Mono.empty();
    if (isMatch) {
      reporter.incrementFilterCount(nodeId, "MATCH");
      result = Mono.just(message);
    } else {
      reporter.incrementFilterCount(nodeId, "DISCARD");
      if (discardPort != null && !discardPort.isBlank()) {
        result = Mono.just(message.withSourcePort(discardPort));
      }
    }
    return result;
  }

  private Mono<Message> handleEvaluationError(
      final String nodeId, final String condition, final boolean strictMode, final Exception err) {
    reporter.incrementFilterCount(nodeId, "ERROR");
    if (log.isErrorEnabled()) {
      log.error("Filter evaluation failed for condition [{}]: {}", condition, err.getMessage());
    }
    if (!strictMode) {
      return Mono.empty();
    }
    throw new FilterEvaluationException(
        "Filter evaluation failed for condition: " + condition, err);
  }

  private boolean evaluate(final String condition, final String engine, final Message message) {
    if (ENGINE_SPEL.equalsIgnoreCase(engine)) {
      final Boolean result = SpelUtils.evaluateSync(condition, message);
      return result != null && result;
    } else if (ENGINE_SIMPLE.equalsIgnoreCase(engine)) {
      return SimpleExpressionEvaluator.evaluate(condition, message);
    }
    throw new IllegalArgumentException("Unsupported engine: " + engine);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String condition = (String) config.get(CONFIG_CONDITION);
    if (condition == null || condition.isBlank()) {
      return Mono.error(new IllegalArgumentException("condition is mandatory"));
    }
    final String engine = (String) config.getOrDefault(CONFIG_ENGINE, ENGINE_SPEL);
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
}
