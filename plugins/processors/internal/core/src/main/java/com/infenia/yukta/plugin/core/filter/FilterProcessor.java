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
package com.infenia.yukta.plugin.core.filter;

import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.core.util.SimpleExpressionEvaluator;
import com.infenia.yukta.plugin.exception.FilterEvaluationException;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.util.SpelUtils;
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
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidCatchingGenericException"})
public final class FilterProcessor implements ProcessorPlugin {

  private static final String TYPE = "FILTER";
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
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex flex-col items-center justify-center h-full space-y-1 relative">
                <svg class="w-8 h-8 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z"/>
                </svg>
                <div class="text-[10px] text-slate-500 font-medium uppercase">Filter</div>
            </div>
            """,
            140,
            80));
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
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
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
      reporter.incrementFilterCount(nodeId, "MATCH");
      return Mono.just(message.withSourcePort("default").withAddedHistory(nodeId));
    } else {
      reporter.incrementFilterCount(nodeId, "DISCARD");
      if (discardPort != null && !discardPort.isBlank()) {
        return Mono.just(message.withSourcePort(discardPort).withAddedHistory(nodeId));
      }
    }
    return Mono.empty();
  }

  private Mono<Message<?>> handleEvaluationError(
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

  private boolean evaluate(final String condition, final String engine, final Message<?> message) {
    if (ENGINE_SPEL.equalsIgnoreCase(engine)) {
      final Map<String, Object> variables =
          Map.of(
              "payload", message.getPayload(),
              "metadata", message.getMetadata());
      final Boolean result = SpelUtils.evaluateSync(condition, message, variables);
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
