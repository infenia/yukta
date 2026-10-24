// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Runs another workflow of the same session for every message and emits what it produced: {@code
 * {executionId, results, status, outcome, reason}}, where {@code results} holds the payload of each
 * message that reached a terminal node of the child and {@code outcome} names the OUTCOME_TERMINAL
 * it reached. The child gets the message payload (a map, or {@code {input: ...}}) as its start
 * payload, or the configured {@code payload} map, whose values may use {@code ${...}} variables
 * (for example {@code ${start.project}} to hand on the parent's start payload). Messages are
 * handled one at a time, or with {@code concurrency} that many at once (results keep the order of
 * the messages). With {@code routeByOutcome} a failed child does not fail this node: the message
 * goes out on a port named after how the child ended, so the parent can branch on it.
 */
@Component
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.UseConcurrentHashMap"})
public final class SubWorkflowProcessor implements ProcessorPlugin {

  /** Config key: the workflow to run. */
  private static final String WORKFLOW_ID = "workflowId";

  /** Config key: the start payload of the child. */
  private static final String PAYLOAD = "payload";

  /** Config key: longest the child may run. */
  private static final String TIMEOUT_SECONDS = "timeoutSeconds";

  /** Config key: route on how the child ended instead of failing when it fails. */
  private static final String ROUTE_BY_OUTCOME = "routeByOutcome";

  /** Config key: how many messages run their child at the same time. */
  private static final String CONCURRENCY_KEY = "concurrency";

  /** Most children one node may run at the same time. */
  private static final int MAX_CONCURRENCY = 16;

  /** Longest wait used when none is configured. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 300;

  /** Runs the child workflows. */
  private final ObjectProvider<SubWorkflowGateway> gateway;

  /** Resolves the variables in the configured start payload. */
  private final ObjectProvider<VariableResolver> resolver;

  /**
   * Creates the plugin.
   *
   * @param gateway runs workflows; absent when the engine offers no sub-workflows
   * @param resolver resolves variables in the configured payload; when absent, values are used as
   *     written
   */
  public SubWorkflowProcessor(
      final ObjectProvider<SubWorkflowGateway> gateway,
      final ObjectProvider<VariableResolver> resolver) {
    this.gateway = gateway;
    this.resolver = resolver;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Runs another workflow of the same session per message and emits its results.",
        """
        Configure with:
        - workflowId: the workflow to run (required); it must exist in the same session.
        - payload: map used as the child's start payload (default: the message payload); its
          values may use ${...} variables, e.g. ${start.project} or ${payload.id}.
        - timeoutSeconds: longest the child may run, default 300.
        - routeByOutcome: true to route on how the child ended (ports success, failure, retry,
          escalate) instead of failing this node when the child fails.
        - concurrency: how many messages run their child at once, default 1 (one at a time), at
          most 16; the results keep the order of the messages.
        Emits {executionId, results[], status, outcome?, reason?}: outcome is the OUTCOME_TERMINAL the
        child reached (SUCCESS, RETRY, ESCALATE, FAILED), absent when it reached none. Nesting is limited to 5 levels. With
        yukta.execution.max-concurrent the limit must allow the parent and its children together.
        """,
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(WORKFLOW_ID, ConfigField.Type.STRING, "Workflow to run"),
        ConfigField.optional(PAYLOAD, ConfigField.Type.MAP, null, "Start payload of the child"),
        ConfigField.optional(
            TIMEOUT_SECONDS, ConfigField.Type.NUMBER, DEFAULT_TIMEOUT_SECONDS, "Longest run"),
        ConfigField.optional(
            ROUTE_BY_OUTCOME, ConfigField.Type.BOOLEAN, false, "Route on how the child ended"),
        ConfigField.optional(
            CONCURRENCY_KEY, ConfigField.Type.NUMBER, 1, "Children running at the same time"));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return routeByOutcome(config) ? SubWorkflowResults.PORTS : getOutputPorts();
  }

  @Override
  public String getType() {
    return "SUB_WORKFLOW";
  }

  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    return timeout(config).plusSeconds(10);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          workflowId(config);
          timeout(config);
          concurrency(config);
          if (config.get(PAYLOAD) != null && !(config.get(PAYLOAD) instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("payload must be a map");
          }
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String workflowId = workflowId(config);
    final Duration timeout = timeout(config);
    final boolean route = routeByOutcome(config);
    final int concurrency = concurrency(config);
    final Function<Message<?>, Mono<Message<?>>> child =
        message ->
            Mono.deferContextual(
                ctx -> {
                  final SubWorkflowGateway runner = gateway.getIfAvailable();
                  if (runner == null) {
                    return Mono.error(new IllegalStateException("Sub-workflows are not available"));
                  }
                  final int depth = depthOf(ctx);
                  return startPayload(message, config)
                      .flatMap(
                          start ->
                              runner.run(
                                  ctx.getOrDefault("sessionId", "unknown"),
                                  workflowId,
                                  start,
                                  ctx.getOrDefault("executionId", "unknown"),
                                  depth + 1))
                      .timeout(timeout)
                      .onErrorResume(
                          e -> route && e instanceof SubWorkflowGateway.ChildFailed,
                          e ->
                              Mono.just(
                                  SubWorkflowResults.failed((SubWorkflowGateway.ChildFailed) e)))
                      .map(
                          result ->
                              route
                                  ? SubWorkflowResults.routed(message, result)
                                  : SubWorkflowResults.message(message, result));
                });
    return concurrency == 1 ? input.concatMap(child) : input.flatMapSequential(child, concurrency);
  }

  /** How deep the running workflow is nested, from the start payload of the run. */
  private static int depthOf(final ContextView ctx) {
    final Object payload = ctx.getOrDefault("payload", null);
    final Object depth =
        payload instanceof Map<?, ?> map ? map.get(SubWorkflowGateway.DEPTH_KEY) : null;
    return depth instanceof Number number ? number.intValue() : 0;
  }

  /**
   * The child's start payload: the configured map with its variables resolved (a value that
   * resolves to nothing is left out), else the message payload.
   */
  private Mono<Map<String, Object>> startPayload(
      final Message<?> message, final Map<String, Object> config) {
    final Mono<Map<String, Object>> start;
    if (config.get(PAYLOAD) instanceof final Map<?, ?> configured) {
      final VariableResolver variables = resolver.getIfAvailable();
      start =
          Flux.fromIterable(configured.entrySet())
              .concatMap(
                  entry ->
                      (variables == null
                              ? Mono.justOrEmpty(entry.getValue())
                              : variables.resolve(entry.getValue(), message))
                          .map(value -> Map.entry(String.valueOf(entry.getKey()), value)))
              .collect(
                  LinkedHashMap::new,
                  (Map<String, Object> map, Map.Entry<String, Object> entry) ->
                      map.put(entry.getKey(), entry.getValue()));
    } else {
      final Map<String, Object> map = new LinkedHashMap<>();
      if (message.payload() instanceof final Map<?, ?> payload) {
        payload.forEach((k, v) -> map.put(String.valueOf(k), v));
      } else {
        map.put("input", message.payload());
      }
      start = Mono.just(map);
    }
    return start;
  }

  private static int concurrency(final Map<String, Object> config) {
    final Object value = config.getOrDefault(CONCURRENCY_KEY, 1);
    if (value instanceof Number number
        && number.intValue() >= 1
        && number.intValue() <= MAX_CONCURRENCY) {
      return number.intValue();
    }
    throw new IllegalArgumentException("concurrency must be a number from 1 to " + MAX_CONCURRENCY);
  }

  private static boolean routeByOutcome(final Map<String, Object> config) {
    return Boolean.TRUE.equals(config.get(ROUTE_BY_OUTCOME))
        || "true".equalsIgnoreCase(String.valueOf(config.get(ROUTE_BY_OUTCOME)));
  }

  private static String workflowId(final Map<String, Object> config) {
    if (config.get(WORKFLOW_ID) instanceof String workflow && !workflow.isBlank()) {
      return workflow;
    }
    throw new IllegalArgumentException("workflowId must be a non-empty workflow id");
  }

  private static Duration timeout(final Map<String, Object> config) {
    final Object value = config.getOrDefault(TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    if (value instanceof Number seconds && seconds.longValue() > 0) {
      return Duration.ofSeconds(seconds.longValue());
    }
    throw new IllegalArgumentException("timeoutSeconds must be a positive number");
  }
}
