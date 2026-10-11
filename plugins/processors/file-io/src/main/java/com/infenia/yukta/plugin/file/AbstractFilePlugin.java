// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Shared plumbing for the file plugins: blocking offload, metrics, and failure routing. */
abstract class AbstractFilePlugin implements ProcessorPlugin {

  /** Config key. */
  protected static final String FAILURE_MODE = "failureMode";

  /** Metrics reporter. */
  private final PluginMetricsReporter reporter;

  /**
   * Creates a plugin reporting to the given reporter.
   *
   * @param reporter metrics reporter
   */
  protected AbstractFilePlugin(final PluginMetricsReporter reporter) {
    this.reporter = reporter;
  }

  /**
   * The failureMode config field.
   *
   * @return the schema field
   */
  protected static ConfigField failureModeField() {
    return ConfigField.choice(
        FAILURE_MODE, false, "ERROR", "What to do on an I/O error", "ERROR", "CONTINUE");
  }

  @Override
  public boolean isBlocking() {
    return true;
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return FailureMode.from(config.get(FAILURE_MODE)) == FailureMode.CONTINUE
        ? List.of("default", PluginGuard.ERROR_PORT)
        : getOutputPorts();
  }

  /**
   * Runs {@code work} per message on the blocking scheduler with metrics and failure handling.
   *
   * @param input the incoming messages
   * @param config the plugin config
   * @param metric the metric name
   * @param work the per-message blocking work
   * @return the output messages
   */
  protected Flux<Message<?>> perMessage(
      final Flux<Message<?>> input,
      final Map<String, Object> config,
      final String metric,
      final FileTask work) {
    final FailureMode mode = FailureMode.from(config.get(FAILURE_MODE));
    return input.concatMap(
        message ->
            PluginGuard.guard(
                Mono.<Message<?>>fromCallable(() -> work.apply(message))
                    .subscribeOn(Schedulers.boundedElastic()),
                message,
                mode,
                reporter,
                metric));
  }

  /** Blocking per-message work. */
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  @FunctionalInterface
  protected interface FileTask {
    /**
     * Does the work.
     *
     * @param message the input message
     * @return the output message
     * @throws Exception on I/O or validation failure
     */
    Message<?> apply(Message<?> message) throws Exception;
  }
}
