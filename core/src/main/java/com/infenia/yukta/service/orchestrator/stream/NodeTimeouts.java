// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Settles a node timeout that depends on the first input.
 *
 * <p>A timeout given as an expression (for example {@code "timeout": "${payload.seconds}"}) is not
 * known when the workflow is compiled, so the node starts with the default. When the node gets its
 * first message, the node config is resolved against it and the timeout is worked out again the
 * same way as at compile time: {@code timeoutSeconds}, else the plugin's default for that config.
 * Write a plugin's own number option as a typed expression ({@code "${payload.secs:int}"}); a plain
 * one resolves to text.
 */
@Slf4j
@UtilityClass
public class NodeTimeouts {

  /** Node config key that sets the idle timeout in seconds. */
  private static final String TIMEOUT_SECONDS = "timeoutSeconds";

  /**
   * Re-times the node on its first input if its config holds expressions.
   *
   * @param input the node's input
   * @param node the node
   * @param plugin the node's plugin
   * @param resolver resolves expressions in the config
   * @param activity the node's activity clock, which receives the new timeout
   * @return {@code input}, unchanged if the config is static
   */
  public Flux<Message<?>> retimeOnFirstInput(
      final Flux<Message<?>> input,
      final WorkflowNode node,
      final Plugin plugin,
      final VariableResolver resolver,
      final NodeActivity activity) {
    final Map<String, Object> config = node.config();
    if (config.values().stream().allMatch(resolver::isStatic)) {
      return input;
    }
    return input.switchOnFirst(
        (first, rest) ->
            first.hasValue()
                ? resolve(config, first.get(), resolver)
                    .doOnNext(resolved -> activity.retime(timeout(resolved, plugin)))
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .addKeyValue("nodeId", node.nodeId())
                              .setCause(error)
                              .log("Could not resolve the node timeout, keeping the default");
                          return Mono.empty();
                        })
                    .thenMany(rest)
                : rest);
  }

  private Mono<Map<String, Object>> resolve(
      final Map<String, Object> config, final Message<?> message, final VariableResolver resolver) {
    return Flux.fromIterable(config.entrySet())
        .concatMap(
            entry ->
                resolver
                    .resolve(entry.getValue(), message)
                    .map(value -> Map.entry(entry.getKey(), value)))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue, HashMap::new);
  }

  private Duration timeout(final Map<String, Object> resolved, final Plugin plugin) {
    final long seconds = seconds(resolved.get(TIMEOUT_SECONDS));
    return seconds > 0 ? Duration.ofSeconds(seconds) : plugin.getDefaultTimeout(resolved);
  }

  /** An expression resolves to text unless it is typed ({@code :int}), so accept both. */
  private long seconds(final Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return value == null ? 0 : Long.parseLong(value.toString().trim());
    } catch (final NumberFormatException e) {
      return 0;
    }
  }
}
