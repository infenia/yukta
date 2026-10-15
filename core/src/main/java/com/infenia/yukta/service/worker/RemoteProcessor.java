// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stands in for a processor plugin on the control plane when its node has {@code engine.placement}:
 * everything about the plugin (type, schema, timeouts, validation, lifecycle) is the local
 * plugin's, only {@link #process} goes to a worker. It sits where {@code processor.process} sits,
 * so retries, {@code onError}, timeouts, valves, checkpoints and status work as for a local node.
 */
public class RemoteProcessor implements ProcessorPlugin {

  /** The plugin on this server, used for everything except running. */
  private final ProcessorPlugin local;

  /** Runs the call on a worker. */
  private final RemoteNodeExecutor executor;

  /** The pool of workers that may run the node. */
  private final String pool;

  /**
   * Creates the stand-in.
   *
   * @param local the plugin on this server
   * @param executor runs the call on a worker
   * @param pool the pool of workers that may run the node
   */
  public RemoteProcessor(
      final ProcessorPlugin local, final RemoteNodeExecutor executor, final String pool) {
    this.local = local;
    this.executor = executor;
    this.pool = pool;
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return executor.run(local.getType(), pool, input, config);
  }

  @Override
  public String getType() {
    return local.getType();
  }

  @Override
  public PluginDescriptor describe() {
    return local.describe();
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return local.getConfigSchema();
  }

  @Override
  public List<String> getOutputPorts() {
    return local.getOutputPorts();
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return local.getOutputPorts(config);
  }

  @Override
  public Duration getDefaultTimeout() {
    return local.getDefaultTimeout();
  }

  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    return local.getDefaultTimeout(config);
  }

  @Override
  public int getDefaultBufferSize() {
    return local.getDefaultBufferSize();
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return local.validateConfig(config);
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    return local.initialize(config);
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    return local.prepare(config);
  }

  @Override
  public Mono<Void> shutdown(final Map<String, Object> config) {
    return local.shutdown(config);
  }

  /**
   * A remote call does not block a control plane thread, whatever the plugin does on the worker.
   *
   * @return false
   */
  @Override
  public boolean isBlocking() {
    return false;
  }

  @Override
  public Mono<Void> validateInContext(
      final WorkflowContext context, final Map<String, Object> config) {
    return local.validateInContext(context, config);
  }

  @Override
  public boolean isComputationallyHeavy(final Map<String, Object> config) {
    return local.isComputationallyHeavy(config);
  }

  @Override
  public boolean suppressOptimizationHint(final Map<String, Object> config) {
    return local.suppressOptimizationHint(config);
  }
}
