// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.assembly.ExecutionContextBuilder;
import com.infenia.yukta.service.orchestrator.stream.NodeActivity;
import com.infenia.yukta.service.orchestrator.stream.NodeStreams;
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

  /** Longest live log line sent in one piece; the tracker accepts 16,384 characters. */
  private static final int MAX_LIVE_LINE_CHARS = 16_000;

  /* default */ static Flux<Message<?>> buildStreamWithContext(
      final WorkflowNode node,
      final Flux<Message<?>> stream,
      final Duration timeout,
      final NodeActivity activity,
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
            .heartbeat(activity::touch)
            .logSink(liveLogSink(tracker, context.executionId(), node.nodeId(), activity))
            .payload(context.payload());

    Flux<Message<?>> built =
        NodeStreams.withTimeoutAndTracking(
            node,
            timeout,
            tracker,
            context.executionId(),
            stream,
            activity,
            () -> control.isPaused(node.nodeId()),
            NodePolicy.from(node.config()).deadline());

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
   * Build the sink a plugin writes live log lines to. Each line goes to the execution log whole;
   * one longer than the tracker accepts is split rather than cut. A line also counts as activity,
   * so a node that is quietly producing output is not idle.
   */
  private static PluginLogSink liveLogSink(
      final TaskTrackerService tracker,
      final String executionId,
      final String nodeId,
      final NodeActivity activity) {
    return (stream, line) -> {
      activity.touch();
      activity.markLoggedLive();
      // The tracker rejects blank lines.
      if (line == null || line.isBlank()) {
        return;
      }
      for (int from = 0; from < line.length(); from += MAX_LIVE_LINE_CHARS) {
        tracker.emitLogEvent(
            executionId,
            nodeId,
            line.substring(from, Math.min(line.length(), from + MAX_LIVE_LINE_CHARS)),
            "stderr".equals(stream));
      }
    };
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
