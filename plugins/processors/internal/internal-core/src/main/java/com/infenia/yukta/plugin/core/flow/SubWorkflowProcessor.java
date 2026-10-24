// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * payload, or the configured {@code payload} map. Messages are handled one at a time. With {@code
 * routeByOutcome} a failed child does not fail this node: the message goes out on a port named
 * after how the child ended, so the parent can branch on it.
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

  /** Longest wait used when none is configured. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 300;

  /** Runs the child workflows. */
  private final ObjectProvider<SubWorkflowGateway> gateway;

  /**
   * Creates the plugin.
   *
   * @param gateway runs workflows; absent when the engine offers no sub-workflows
   */
  public SubWorkflowProcessor(final ObjectProvider<SubWorkflowGateway> gateway) {
    this.gateway = gateway;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Runs another workflow of the same session per message and emits its results.",
        """
        Configure with:
        - workflowId: the workflow to run (required); it must exist in the same session.
        - payload: map used as the child's start payload (default: the message payload).
        - timeoutSeconds: longest the child may run, default 300.
        - routeByOutcome: true to route on how the child ended (ports success, failure, retry,
          escalate) instead of failing this node when the child fails.
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
            ROUTE_BY_OUTCOME, ConfigField.Type.BOOLEAN, false, "Route on how the child ended"));
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
    return input.concatMap(
        message ->
            Mono.deferContextual(
                ctx -> {
                  final SubWorkflowGateway runner = gateway.getIfAvailable();
                  if (runner == null) {
                    return Mono.error(new IllegalStateException("Sub-workflows are not available"));
                  }
                  final int depth = depthOf(ctx);
                  return runner
                      .run(
                          ctx.getOrDefault("sessionId", "unknown"),
                          workflowId,
                          startPayload(message, config),
                          ctx.getOrDefault("executionId", "unknown"),
                          depth + 1)
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
                }));
  }

  /** How deep the running workflow is nested, from the start payload of the run. */
  private static int depthOf(final ContextView ctx) {
    final Object payload = ctx.getOrDefault("payload", null);
    final Object depth =
        payload instanceof Map<?, ?> map ? map.get(SubWorkflowGateway.DEPTH_KEY) : null;
    return depth instanceof Number number ? number.intValue() : 0;
  }

  private static Map<String, Object> startPayload(
      final Message<?> message, final Map<String, Object> config) {
    final Map<String, Object> start = new LinkedHashMap<>();
    if (config.get(PAYLOAD) instanceof final Map<?, ?> configured) {
      configured.forEach((k, v) -> start.put(String.valueOf(k), v));
    } else if (message.payload() instanceof final Map<?, ?> map) {
      map.forEach((k, v) -> start.put(String.valueOf(k), v));
    } else {
      start.put("input", message.payload());
    }
    return start;
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
