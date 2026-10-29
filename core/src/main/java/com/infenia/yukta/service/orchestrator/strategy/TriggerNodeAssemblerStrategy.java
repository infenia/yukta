// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.channel.NodeMessageChannel;
import com.infenia.yukta.message.channel.NodeMessageChannelProvider;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.orchestrator.stream.NodeActivity;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/** Strategy for assembling trigger nodes that initiate workflow execution. */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class TriggerNodeAssemblerStrategy implements NodeAssemblerStrategy {

  /** The task tracker service for tracking node execution. */
  private final TaskTrackerService tracker;

  /** The virtual thread scheduler for non-blocking execution. */
  private final Scheduler virtualThreadScheduler;

  /** The stream topology decorator for customizing stream behavior. */
  private final StreamTopologyDecorator streamTopologyDecorator;

  /** The node message channel provider for creating message channels. */
  private final NodeMessageChannelProvider channelProvider;

  /** The registry of plugin instances actively servicing workflow nodes. */
  private final ActivePluginRegistry activePluginRegistry;

  @Override
  public boolean supports(final Plugin plugin, final boolean hasParents) {
    final boolean isSupported =
        plugin instanceof TriggerPlugin
            && (plugin.getCategory() == PluginCategory.TRIGGER || !hasParents);
    if (isSupported) {
      log.atDebug()
          .addKeyValue("pluginType", plugin.getClass().getSimpleName())
          .addKeyValue("hasParents", hasParents)
          .log("TriggerNodeAssemblerStrategy supports this plugin");
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
    final TriggerPlugin trigger =
        NodePlugins.forNode((TriggerPlugin) plugin, TriggerPlugin.class, node.config());

    log.atDebug()
        .addKeyValue("nodeId", node.nodeId())
        .addKeyValue("pluginType", plugin.getClass().getSimpleName())
        .addKeyValue("isBlocking", trigger.isBlocking())
        .addKeyValue("timeoutMs", timeout.toMillis())
        .addKeyValue("bufferSize", bufferSize)
        .log("Creating trigger node assembler");

    return context -> {
      final var control = context.control();

      log.atDebug()
          .addKeyValue("nodeId", node.nodeId())
          .addKeyValue("executionId", context.executionId())
          .log("Starting trigger stream");

      final NodeMessageChannel channel = channelProvider.channelFor(node.nodeId(), node.config());
      Flux<Message<?>> stream =
          channel.outbound(node.nodeId(), context.executionId(), trigger.start(node.config()));
      if (trigger.isBlocking()) {
        log.atDebug()
            .addKeyValue("nodeId", node.nodeId())
            .log("Subscribing blocking trigger to virtual thread scheduler");
        stream = stream.subscribeOn(virtualThreadScheduler);
      }

      // A trigger has no upstream: its clock starts as soon as it is subscribed.
      final NodeActivity activity = new NodeActivity();
      stream = stream.doOnSubscribe(_ -> activity.touch());

      Flux<Message<?>> built =
          StreamAssemblyHelper.buildStreamWithContext(
              node, stream, timeout, activity, tracker, context, plugin, activePluginRegistry);

      // A trigger has no input to gate, so pause and step hold its output instead.
      built = control.applyPassageGate(node.nodeId(), built);

      final var nodeSafeSink = control.nodeSafeStopSinks().get(node.nodeId());
      if (nodeSafeSink != null) {
        log.atDebug()
            .addKeyValue("nodeId", node.nodeId())
            .log("Applying safe stop signal to trigger stream");
        built = built.takeUntilOther(nodeSafeSink.asMono());
      }

      context.streams()[index] =
          streamTopologyDecorator.applyLoggingAndBroadcasting(
              context.executionId(),
              node.nodeId(),
              built,
              bufferSize,
              context.disposables(),
              context.connectors());

      log.atDebug()
          .addKeyValue("nodeId", node.nodeId())
          .addKeyValue("executionId", context.executionId())
          .log("Trigger node stream assembled and registered");
    };
  }
}
