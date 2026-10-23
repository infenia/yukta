// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.channel.NodeMessageChannel;
import com.infenia.yukta.message.channel.NodeMessageChannelProvider;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.VariableResolver;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.orchestrator.stream.FailureLedger;
import com.infenia.yukta.service.orchestrator.stream.NodeActivity;
import com.infenia.yukta.service.orchestrator.stream.NodePolicies;
import com.infenia.yukta.service.orchestrator.stream.NodeTimeouts;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.stream.UpstreamFailure;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
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

  /** The registry of plugin instances actively servicing workflow nodes. */
  private final ActivePluginRegistry activePluginRegistry;

  /** Resolves expressions in the node config, for a timeout that depends on the first input. */
  private final VariableResolver variableResolver;

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

  /** Whether this is a repeat of a message the plugin already failed on. */
  private static boolean retried(final Message<?> attempt) {
    // NodePolicies.apply sets the header on every attempt
    return (Integer) attempt.metadata().get(NodePolicies.ATTEMPT_HEADER) > 1;
  }

  /**
   * Lets elements skip the plugin while the node's skip flag is set. The decision is taken once per
   * element, when it arrives, so a skip applies from the next element on; an element the plugin
   * already has finishes normally. Skipped elements go straight to the output.
   */
  private static Flux<Message<?>> bypassWhileSkipped(
      final Flux<Message<?>> input,
      final AtomicBoolean skipFlag,
      final Function<Flux<Message<?>>, Flux<Message<?>>> run) {
    return input
        .map(message -> Map.entry(skipFlag.get(), message))
        // prefetch 1 keeps a paused node from running ahead of its gate
        .publish(
            routed ->
                Flux.merge(
                    run.apply(routed.filter(entry -> !entry.getKey()).map(Map.Entry::getValue)),
                    routed.filter(Map.Entry::getKey).map(Map.Entry::getValue)),
            1);
  }

  @Override
  public NodeAssembler createAssembler(
      final WorkflowNode node,
      final Plugin plugin,
      final Duration timeout,
      final int index,
      final int bufferSize,
      final ParentEdgeInfo... parentEdges) {
    // one object per node: its state must not leak into another node of the same type
    final ProcessorPlugin shared = (ProcessorPlugin) plugin;
    final Plugin perNode = shared.forNode(node.config());
    final ProcessorPlugin processor = perNode instanceof ProcessorPlugin p ? p : shared;

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
          streamTopologyDecorator.mergeParentStreams(
              NodePolicy.from(node.config()).trigger(), context.streams(), parentEdges);

      // The idle clock starts when this node first gets work, not when the run starts.
      final NodeActivity activity = new NodeActivity();
      final Flux<Message<?>> safeInput =
          control
              .applyPreProcessingControls(node.nodeId(), mergedInput)
              .doOnNext(_ -> activity.touch())
              .onErrorMap(UpstreamFailure::mark);
      final Flux<Message<?>> timedInput =
          NodeTimeouts.retimeOnFirstInput(safeInput, node, plugin, variableResolver, activity);

      Flux<Message<?>> stream;
      final AtomicBoolean skipFlag = control.nodeSkipFlags().get(node.nodeId());
      // The pipeline is always built, even for a node that starts out skipped: the skip flag is
      // read per element (bypassWhileSkipped), so an unskip later takes effect.
      log.atDebug()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .log("Processing stream with processor plugin");
      final NodeMessageChannel channel = channelProvider.channelFor(node.nodeId(), node.config());
      final NodePolicy policy = NodePolicy.from(node.config());
      final Flux<Message<?>> channelInput =
          channel.inbound(
              node.nodeId(),
              context.executionId(),
              NodePolicies.edgeBuffered(
                  NodePolicy.edgeBuffer(node.config()), timedInput, tracker, node.type()));
      // failures are counted per run, so the ledger belongs to this assembly, not to the node
      final FailureLedger ledger =
          policy.failureBudget() == null ? null : new FailureLedger(policy.failureBudget());
      final Function<Flux<Message<?>>, Flux<Message<?>>> run =
          policy.perMessage()
              // prefetch 1: a paused node must not have 32 elements already past the gate
              ? input ->
                  input.concatMap(
                      message ->
                          NodePolicies.apply(
                              policy,
                              node.nodeId(),
                              message,
                              attempt -> {
                                if (retried(attempt)) {
                                  tracker.recordRetry(node.type());
                                }
                                return processor.process(Flux.just(attempt), node.config());
                              },
                              activity::touch,
                              ledger),
                      1)
              : input -> processor.process(input, node.config());
      final Flux<Message<?>> pluginOutput =
          skipFlag == null
              ? run.apply(channelInput)
              : bypassWhileSkipped(channelInput, skipFlag, run);
      stream = channel.outbound(node.nodeId(), context.executionId(), pluginOutput);
      if (processor.isBlocking()) {
        log.atDebug()
            .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
            .log("Subscribing blocking processor to virtual thread scheduler");
        stream = stream.subscribeOn(virtualThreadScheduler);
      }

      final Flux<Message<?>> built =
          StreamAssemblyHelper.buildStreamWithContext(
              node, stream, timeout, activity, tracker, context, plugin, activePluginRegistry);
      context.streams()[index] =
          streamTopologyDecorator.applyLoggingAndBroadcasting(
              context.executionId(),
              node.nodeId(),
              built,
              bufferSize,
              context.disposables(),
              context.connectors(),
              activity::hasLoggedLive);

      log.atDebug()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .addKeyValue("executionId", context.executionId())
          .log("Processor node stream assembled and registered");
    };
  }
}
