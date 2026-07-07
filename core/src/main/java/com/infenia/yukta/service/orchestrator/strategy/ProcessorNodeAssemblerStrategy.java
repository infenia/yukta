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
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/** Strategy for assembling processor nodes with transformation logic. */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class ProcessorNodeAssemblerStrategy implements NodeAssemblerStrategy {

  /** The task tracker service for tracking node execution. */
  private final TaskTrackerService tracker;

  /** The virtual thread scheduler for non-blocking execution. */
  private final Scheduler virtualThreadScheduler;

  /** The stream topology decorator for customizing stream behavior. */
  private final StreamTopologyDecorator streamTopologyDecorator;

  /** The node message channel provider for creating message channels. */
  private final NodeMessageChannelProvider channelProvider;

  /** Log key for node ID. */
  private static final String LOG_KEY_NODE_ID = "nodeId";

  /** Log key for parent edge count. */
  private static final String LOG_KEY_PARENT_EDGE_COUNT = "parentEdgeCount";

  @Override
  public boolean supports(final Plugin plugin, final boolean hasParents) {
    final boolean isSupported = plugin instanceof ProcessorPlugin;
    if (isSupported) {
      log.atDebug()
          .addKeyValue("pluginType", plugin.getClass().getSimpleName())
          .log("ProcessorNodeAssemblerStrategy supports this plugin");
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
      final ParentEdgeInfo... parentEdges) {
    final ProcessorPlugin processor = (ProcessorPlugin) plugin;

    log.atDebug()
        .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
        .addKeyValue(LOG_KEY_PARENT_EDGE_COUNT, parentEdges.length)
        .addKeyValue("pluginType", plugin.getClass().getSimpleName())
        .addKeyValue("isBlocking", processor.isBlocking())
        .addKeyValue("timeoutMs", timeout.toMillis())
        .addKeyValue("bufferSize", bufferSize)
        .log("Creating processor node assembler");

    return context -> {
      final var control = context.control();

      log.atDebug()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .addKeyValue("executionId", context.executionId())
          .log("Merging input from parent streams");

      final Flux<Message<?>> mergedInput =
          streamTopologyDecorator.mergeParentStreams(context.streams(), parentEdges);

      final Flux<Message<?>> safeInput =
          control.applyPreProcessingControls(node.nodeId(), mergedInput);

      Flux<Message<?>> stream;
      final AtomicBoolean skipFlag = control.nodeSkipFlags().get(node.nodeId());

      if (skipFlag != null && skipFlag.get()) {
        log.atInfo()
            .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
            .addKeyValue("executionId", context.executionId())
            .log("Node marked for skip, bypassing processor");
        stream = safeInput;
      } else {
        log.atDebug()
            .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
            .log("Processing stream with processor plugin");
        final NodeMessageChannel channel = channelProvider.channelFor(node.nodeId(), node.config());
        final Flux<Message<?>> channelInput =
            channel.inbound(node.nodeId(), context.executionId(), safeInput);
        final Flux<Message<?>> pluginOutput = processor.process(channelInput, node.config());
        stream = channel.outbound(node.nodeId(), context.executionId(), pluginOutput);
        if (processor.isBlocking()) {
          log.atDebug()
              .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
              .log("Subscribing blocking processor to virtual thread scheduler");
          stream = stream.subscribeOn(virtualThreadScheduler);
        }
      }

      final Flux<Message<?>> built =
          StreamAssemblyHelper.buildStreamWithContext(node, stream, timeout, tracker, context);
      context.streams()[index] =
          streamTopologyDecorator.applyLoggingAndBroadcasting(
              context.executionId(),
              node.nodeId(),
              built,
              bufferSize,
              context.disposables(),
              context.connectors());

      log.atDebug()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .addKeyValue("executionId", context.executionId())
          .log("Processor node stream assembled and registered");
    };
  }
}
