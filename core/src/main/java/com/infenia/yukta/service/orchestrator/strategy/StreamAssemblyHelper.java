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
package com.infenia.yukta.service.orchestrator.strategy;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.assembly.ExecutionContextBuilder;
import com.infenia.yukta.service.orchestrator.stream.StreamBuilder;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Helper class for assembling reactive streams with execution context and control. */
@Slf4j
@UtilityClass
class StreamAssemblyHelper {

  /* default */ static Flux<Message<?>> buildStreamWithContext(
      final WorkflowNode node,
      final Flux<Message<?>> stream,
      final Duration timeout,
      final TaskTrackerService tracker,
      final AssemblyContext context,
      final Plugin plugin,
      final ActivePluginRegistry activePluginRegistry) {

    log.atDebug()
        .addKeyValue("nodeId", node.nodeId())
        .addKeyValue("executionId", context.executionId())
        .addKeyValue("workflowId", context.workflowId())
        .addKeyValue("timeout", timeout)
        .log("Assembling stream for node");

    final ExecutionControl control = context.control();
    final ExecutionContextBuilder contextBuilder =
        new ExecutionContextBuilder()
            .sessionId(context.sessionId())
            .workflowId(context.workflowId())
            .executionId(context.executionId())
            .nodeId(node.nodeId())
            .payload(context.payload());

    Flux<Message<?>> built =
        new StreamBuilder(node, timeout, tracker)
            .withSource(stream)
            .withTimeout()
            .withTaskTracking(context.executionId())
            .withErrorHandling(context.executionId())
            .build();

    built =
        withPluginLifecycle(
            built, context.workflowId(), node.nodeId(), plugin, activePluginRegistry);

    built = control.applyPostProcessingControls(node.nodeId(), built);
    return contextBuilder
        .applyContextTo(built)
        .doOnError(
            error ->
                log.atError()
                    .addKeyValue("nodeId", node.nodeId())
                    .addKeyValue("executionId", context.executionId())
                    .setCause(error)
                    .log("Stream assembly failed for node"));
  }

  /**
   * Wrap a node's stream so its plugin is registered with {@link ActivePluginRegistry} on subscribe
   * and unregistered when the stream terminates (success, error, or cancellation).
   *
   * @param flux the node's stream
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @param plugin the plugin instance servicing the node
   * @param activePluginRegistry the registry to register/unregister the plugin with
   * @return the stream wrapped with plugin lifecycle registration
   */
  /* default */ static Flux<Message<?>> withPluginLifecycle(
      final Flux<Message<?>> flux,
      final String workflowId,
      final String nodeId,
      final Plugin plugin,
      final ActivePluginRegistry activePluginRegistry) {
    return flux.doOnSubscribe(s -> activePluginRegistry.register(workflowId, nodeId, plugin))
        .doFinally(signal -> activePluginRegistry.unregister(workflowId, nodeId));
  }

  /**
   * Wrap a terminal node's completion signal so its plugin is registered with {@link
   * ActivePluginRegistry} on subscribe and unregistered when it terminates (success, error, or
   * cancellation).
   *
   * @param mono the terminal node's completion signal
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @param plugin the plugin instance servicing the node
   * @param activePluginRegistry the registry to register/unregister the plugin with
   * @return the completion signal wrapped with plugin lifecycle registration
   */
  /* default */ static Mono<Void> withPluginLifecycle(
      final Mono<Void> mono,
      final String workflowId,
      final String nodeId,
      final Plugin plugin,
      final ActivePluginRegistry activePluginRegistry) {
    return mono.doOnSubscribe(s -> activePluginRegistry.register(workflowId, nodeId, plugin))
        .doFinally(signal -> activePluginRegistry.unregister(workflowId, nodeId));
  }
}
