// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.RunAbortedException;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wraps one per-message operation with the platform's shared failure handling and metrics, so
 * plugins don't each hand-roll them: counts {@code OK}/{@code ERROR} through the {@link
 * PluginMetricsReporter}, and on failure either propagates (ERROR) or routes the input to the
 * {@code error} port with {@link Message#withFailure} details (CONTINUE).
 */
@UtilityClass
public class PluginGuard {

  /** Output port failures are routed to under {@link FailureMode#CONTINUE}. */
  public static final String ERROR_PORT = "error";

  /**
   * Runs {@code operation} for {@code input} with metrics and failure handling.
   *
   * @param operation the work producing the output message
   * @param input the message being processed (re-emitted on the error port when continuing)
   * @param mode what to do when the operation fails
   * @param reporter receives {@code OK}/{@code ERROR} counts under {@code metric}
   * @param metric the metric name, usually the plugin type
   * @return the operation result, the error-port message, or the original error
   */
  public static Mono<Message<?>> guard(
      final Mono<Message<?>> operation,
      final Message<?> input,
      final FailureMode mode,
      final PluginMetricsReporter reporter,
      final String metric) {
    return Mono.deferContextual(
        ctx -> {
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");
          return operation
              .doOnNext(m -> reporter.increment(nodeId, metric, "OK"))
              .onErrorResume(
                  e -> {
                    reporter.increment(nodeId, metric, "ERROR");
                    // a hard abort ends the run whatever the node's own failureMode says
                    if (mode == FailureMode.ERROR || RunAbortedException.isAbort(e)) {
                      return Mono.error(e);
                    }
                    return Mono.just(
                        input
                            .withFailure(nodeId, e.getClass().getSimpleName(), e.getMessage())
                            .withSourcePort(ERROR_PORT));
                  });
        });
  }

  /**
   * Counts each emitted element as {@code OK} and a terminal error as {@code ERROR}, for plugins
   * that handle their own failures but still want to report them.
   *
   * @param <T> the element type
   * @param flux the plugin output
   * @param reporter receives the counts
   * @param metric the metric name, usually the plugin type
   * @return the same elements and signals
   */
  public static <T> Flux<T> counted(
      final Flux<T> flux, final PluginMetricsReporter reporter, final String metric) {
    return Flux.deferContextual(
        ctx -> {
          final String nodeId = ctx.getOrDefault("nodeId", "unknown");
          return flux.doOnNext(m -> reporter.increment(nodeId, metric, "OK"))
              .doOnError(e -> reporter.increment(nodeId, metric, "ERROR"));
        });
  }
}
