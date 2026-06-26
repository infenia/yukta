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
import com.infenia.yukta.message.channel.NodeMessageChannel;
import com.infenia.yukta.message.channel.NodeMessageChannelProvider;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.gateway.ResultCollector;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Strategy for assembling terminal nodes that finalize workflow execution. */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class TerminalNodeAssemblerStrategy implements NodeAssemblerStrategy {

  /** Buffer size for workflow streams. */
  private static final int BUFFER_SIZE = 1024;

  /** Default task identifier. */
  private static final String DEFAULT_TASK_ID = "default";

  /** Status value for running execution. */
  private static final String STATUS_RUNNING = "RUNNING";

  /** Status value for successful execution. */
  private static final String STATUS_SUCCESS = "SUCCESS";

  /** Status value for failed execution. */
  private static final String STATUS_FAILURE = "FAILURE";

  /** Log key for node ID. */
  private static final String LOG_KEY_NODE_ID = "nodeId";

  /** Log key for parent edge count. */
  private static final String LOG_KEY_PARENT_EDGE_COUNT = "parentEdgeCount";

  /** The task tracker service for tracking node execution. */
  private final TaskTrackerService tracker;

  /** The virtual thread scheduler for non-blocking execution. */
  private final Scheduler virtualThreadScheduler;

  /** The stream topology decorator for customizing stream behavior. */
  private final StreamTopologyDecorator streamTopologyDecorator;

  /** The node message channel provider for creating message channels. */
  private final NodeMessageChannelProvider channelProvider;

  @Override
  public boolean supports(final Plugin plugin, final boolean hasParents) {
    final boolean isSupported = plugin instanceof TerminalPlugin;
    if (isSupported) {
      log.atDebug()
          .addKeyValue("pluginType", plugin.getClass().getSimpleName())
          .log("TerminalNodeAssemblerStrategy supports this plugin");
    }
    return isSupported;
  }

  @Override
  public NodeAssembler createAssembler(
      final WorkflowNode node,
      final Plugin plugin,
      final Duration timeout,
      final int index,
      final int bufferSize,
      final ParentEdgeInfo[] parentEdges) {
    final TerminalPlugin terminal = (TerminalPlugin) plugin;

    log.atDebug()
        .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
        .addKeyValue(LOG_KEY_PARENT_EDGE_COUNT, parentEdges.length)
        .addKeyValue("pluginType", plugin.getClass().getSimpleName())
        .addKeyValue("isBlocking", terminal.isBlocking())
        .addKeyValue("timeoutMs", timeout.toMillis())
        .log("Creating terminal node assembler");

    return context -> {
      final var control = context.control();

      log.atDebug()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .addKeyValue("executionId", context.executionId())
          .log("Merging input from parent streams for terminal node");

      final Flux<Message<?>> mergedInput =
          streamTopologyDecorator.mergeParentStreams(context.streams(), parentEdges);

      final Flux<Message<?>> safeInput =
          control.applyPreProcessingControls(node.nodeId(), mergedInput);

      final Flux<Message<?>> inputToTerminal =
          safeInput
              .flatMap(
                  msg ->
                      Mono.<Message<?>>just(msg)
                          .timeout(timeout, virtualThreadScheduler)
                          .onErrorMap(TimeoutException.class, e -> e),
                  BUFFER_SIZE)
              .transformDeferredContextual(
                  (flux, ctx) -> {
                    final ResultCollector collector = ctx.getOrDefault("resultCollector", null);
                    if (collector != null) {
                      log.atDebug()
                          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                          .log("Terminal node is collecting results");
                      return flux.doOnNext(collector::add);
                    }
                    return flux;
                  });

      log.atDebug()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .addKeyValue("executionId", context.executionId())
          .log("Consuming stream with terminal plugin");

      final NodeMessageChannel channel = channelProvider.channelFor(node.nodeId(), node.config());
      Mono<Void> completion =
          terminal.consume(
              channel.inbound(node.nodeId(), context.executionId(), inputToTerminal),
              node.config());
      if (terminal.isBlocking()) {
        log.atDebug()
            .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
            .log("Subscribing blocking terminal to virtual thread scheduler");
        completion = completion.subscribeOn(virtualThreadScheduler);
      }

      completion =
          completion
              .doOnSubscribe(
                  s -> {
                    log.atDebug()
                        .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                        .addKeyValue("executionId", context.executionId())
                        .log("Terminal node execution started");
                    tracker.emitTaskStatusEvent(
                        context.executionId(),
                        node.nodeId(),
                        DEFAULT_TASK_ID,
                        STATUS_RUNNING,
                        Collections.emptyMap());
                  })
              .doOnSuccess(
                  v -> {
                    log.atInfo()
                        .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                        .addKeyValue("executionId", context.executionId())
                        .log("Terminal node execution completed successfully");
                    tracker.emitTaskStatusEvent(
                        context.executionId(),
                        node.nodeId(),
                        DEFAULT_TASK_ID,
                        STATUS_SUCCESS,
                        Collections.emptyMap());
                  })
              .doOnError(
                  e -> {
                    log.atWarn()
                        .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                        .addKeyValue("executionId", context.executionId())
                        .addKeyValue("error", e.getClass().getSimpleName())
                        .log("Terminal node execution failed", e);
                    tracker.emitTaskStatusEvent(
                        context.executionId(),
                        node.nodeId(),
                        DEFAULT_TASK_ID,
                        STATUS_FAILURE,
                        Collections.emptyMap());
                  });

      context.terminals().add(completion);
    };
  }
}
