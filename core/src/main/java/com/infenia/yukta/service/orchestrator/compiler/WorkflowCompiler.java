// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.compiler;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowEdge;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.strategy.NodeAssemblerStrategy;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Compiles workflow templates into executable reactive streams with plugin integration. */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.AvoidDuplicateLiterals"})
public class WorkflowCompiler {

  /** Buffer size for workflow streams. */
  private static final int BUFFER_SIZE = 1024;

  /** Reference count timeout in seconds for disposing resources. */
  private static final long REF_COUNT_TIMEOUT = 30L;

  /** Context key for session ID. */
  private static final String CTX_SESSION_ID = "sessionId";

  /** Context key for workflow ID. */
  private static final String CTX_WORKFLOW_ID = "workflowId";

  /** Context key for payload. */
  private static final String CTX_PAYLOAD = "payload";

  /** Log key for execution ID. */
  private static final String LOG_KEY_EXECUTION_ID = "executionId";

  /** Log key for node count. */
  private static final String LOG_KEY_NODE_COUNT = "nodeCount";

  /** Log key for node IDs. */
  private static final String LOG_KEY_NODE_IDS = "nodeIds";

  /** Log key for heartbeat interval. */
  private static final String LOG_KEY_HEARTBEAT_INTERVAL = "heartbeatInterval";

  /** Log key for terminal count. */
  private static final String LOG_KEY_TERMINAL_COUNT = "terminalCount";

  /** Log key for node ID. */
  private static final String LOG_KEY_NODE_ID = "nodeId";

  /** Log key for plugin type. */
  private static final String LOG_KEY_PLUGIN_TYPE = "pluginType";

  /** The task tracker service for tracking workflow execution. */
  private final TaskTrackerService tracker;

  /** The virtual thread scheduler for non-blocking execution. */
  private final Scheduler virtualThreadScheduler;

  /** The heartbeat interval duration. */
  private final Duration heartbeatInterval;

  /** The session configuration store for accessing session settings. */
  private final SessionConfigStore configService;

  /** The execution control registry for managing workflow execution control. */
  private final ExecutionControlRegistry executionControlRegistry;

  /** The list of node assembler strategies for compiling different node types. */
  private final List<NodeAssemblerStrategy> assemblerStrategies;

  /**
   * Compiles a workflow template for high-performance execution.
   *
   * @param edges the workflow edges
   * @param parentsList map of nodeId to parent nodes
   * @param pluginCache map of nodeId to initialized plugin instances
   * @param topologicalOrder list of nodes in topological order
   * @return the compiled workflow template
   */
  public WorkflowTemplate compileTemplate(
      final List<WorkflowEdge> edges,
      final Map<String, List<WorkflowNode>> parentsList,
      final Map<String, Plugin> pluginCache,
      final List<WorkflowNode> topologicalOrder) {

    final int nodeCount = topologicalOrder.size();
    log.atDebug()
        .addKeyValue(LOG_KEY_NODE_COUNT, nodeCount)
        .addKeyValue("edgeCount", edges.size())
        .log("Starting workflow template compilation");

    final NodeAssembler[] assemblers =
        compileAssemblers(edges, parentsList, pluginCache, topologicalOrder);

    final List<String> nodeIds = topologicalOrder.stream().map(WorkflowNode::nodeId).toList();
    log.atDebug()
        .addKeyValue(LOG_KEY_NODE_COUNT, nodeCount)
        .log("Workflow template compiled successfully");

    return (executionId, payload) ->
        Mono.deferContextual(
            ctx ->
                Mono.defer(
                        () ->
                            executeTemplate(
                                executionId,
                                payload,
                                nodeCount,
                                assemblers,
                                ctx.get(CTX_SESSION_ID),
                                ctx.get(CTX_WORKFLOW_ID),
                                nodeIds))
                    .contextWrite(c -> c.put(CTX_PAYLOAD, payload)));
  }

  /**
   * Builds an assembler array for the given workflow in topological order.
   *
   * @param edges the workflow edges
   * @param parentsList map of nodeId to parent nodes
   * @param pluginCache map of nodeId to initialized plugin instances
   * @param topologicalOrder list of nodes in topological order
   * @return the node assembler array, indexed by topological position
   */
  public NodeAssembler[] compileAssemblers(
      final List<WorkflowEdge> edges,
      final Map<String, List<WorkflowNode>> parentsList,
      final Map<String, Plugin> pluginCache,
      final List<WorkflowNode> topologicalOrder) {

    final int nodeCount = topologicalOrder.size();
    log.atDebug()
        .addKeyValue(LOG_KEY_NODE_COUNT, nodeCount)
        .log("Building assembler array for topological order");

    final Map<String, Integer> nodeToIndex = new ConcurrentHashMap<>(nodeCount);
    for (int i = 0; i < nodeCount; i++) {
      nodeToIndex.put(topologicalOrder.get(i).nodeId(), i);
    }

    final NodeAssembler[] assemblers = new NodeAssembler[nodeCount];
    int successCount = 0;
    for (int i = 0; i < nodeCount; i++) {
      final WorkflowNode node = topologicalOrder.get(i);
      assemblers[i] = createNodeAssembler(edges, node, parentsList, pluginCache, nodeToIndex);
      if (assemblers[i] != null) {
        successCount++;
      }
    }

    log.atDebug()
        .addKeyValue(LOG_KEY_NODE_COUNT, nodeCount)
        .addKeyValue("assemblersCreated", successCount)
        .log("Completed assembler compilation");
    return assemblers;
  }

  /**
   * Internal execution logic for a compiled template.
   *
   * @param executionId the execution ID
   * @param payload the initial payload
   * @param nodeCount the number of nodes in the workflow
   * @param assemblers the node assemblers
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @param nodeIds the list of node IDs
   * @return a Mono that completes when execution is finished
   */
  public Mono<Void> executeTemplate(
      final String executionId,
      final Map<String, Object> payload,
      final int nodeCount,
      final NodeAssembler[] assemblers,
      final String sessionId,
      final String workflowId,
      final List<String> nodeIds) {

    log.atDebug()
        .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
        .addKeyValue(LOG_KEY_NODE_COUNT, nodeCount)
        .addKeyValue(LOG_KEY_NODE_IDS, nodeIds)
        .log("Executing workflow template with {} nodes", nodeCount);

    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[nodeCount];
    final List<Mono<Void>> terminals = new ArrayList<>(nodeCount);
    final List<Disposable> disposables = new ArrayList<>(nodeCount);
    final List<Runnable> connectors = new ArrayList<>(nodeCount);

    final var control =
        executionControlRegistry
            .findByExecutionId(executionId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "ExecutionControl not registered for execution: " + executionId));

    final AssemblyContext context =
        new AssemblyContext(
            executionId,
            sessionId,
            workflowId,
            payload,
            control,
            streams,
            terminals,
            disposables,
            connectors);

    for (final NodeAssembler assembler : assemblers) {
      assembler.assemble(context);
    }

    log.atTrace()
        .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
        .addKeyValue(LOG_KEY_TERMINAL_COUNT, terminals.size())
        .log("Node assembly complete");

    // Setup heartbeats and statistics
    log.atDebug()
        .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
        .addKeyValue(LOG_KEY_NODE_COUNT, nodeIds.size())
        .addKeyValue(LOG_KEY_HEARTBEAT_INTERVAL, heartbeatInterval.toMillis())
        .log("Starting heartbeat and statistics emission for {} nodes", nodeIds.size());

    final HeartbeatBuilder heartbeatBuilder =
        new HeartbeatBuilder(heartbeatInterval, virtualThreadScheduler);
    final List<Disposable> heartbeatDisposables =
        heartbeatBuilder
            .forNodes(workflowId, nodeIds)
            .withHeartbeatInterval(heartbeatInterval)
            .withStatisticsInterval(heartbeatInterval.multipliedBy(2))
            .build();
    disposables.addAll(heartbeatDisposables);

    // Execute with resource management
    return new ResourceManagementBuilder(tracker, configService, virtualThreadScheduler)
        .withDisposables(disposables)
        .withTerminals(terminals)
        .withConnectors(connectors)
        .withExecutionTimeout(sessionId, executionId)
        .buildAndExecute();
  }

  /**
   * Creates a NodeAssembler for a specific node.
   *
   * @param edges the workflow edges
   * @param node the node to assemble
   * @param parentsList map of nodeId to parent nodes
   * @param pluginCache map of nodeId to initialized plugin instances
   * @param nodeToIndex map of nodeId to stream array index
   * @return the NodeAssembler
   */
  private NodeAssembler createNodeAssembler(
      final List<WorkflowEdge> edges,
      final WorkflowNode node,
      final Map<String, List<WorkflowNode>> parentsList,
      final Map<String, Plugin> pluginCache,
      final Map<String, Integer> nodeToIndex) {

    final Plugin plugin = pluginCache.get(node.nodeId());
    final Duration nodeTimeout = getNodeTimeout(node, plugin);
    final int bufferSize = getBufferSize(node, plugin);
    final boolean hasParents = !parentsList.get(node.nodeId()).isEmpty();
    final int nodeIndex = nodeToIndex.get(node.nodeId());
    final int parentCount = hasParents ? parentsList.get(node.nodeId()).size() : 0;

    final ParentEdgeInfo[] parentEdges =
        edges.stream()
            .filter(e -> e.target().equals(node.nodeId()))
            .map(e -> new ParentEdgeInfo(nodeToIndex.get(e.source()), e.source(), e.sourcePort()))
            .toArray(ParentEdgeInfo[]::new);

    log.atTrace()
        .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
        .addKeyValue("nodeIndex", nodeIndex)
        .addKeyValue("parentCount", parentCount)
        .addKeyValue("parentEdgeCount", parentEdges.length)
        .log("Creating assembler for node");

    return assemblerStrategies.stream()
        .filter(strategy -> strategy.supports(plugin, hasParents))
        .findFirst()
        .map(
            strategy -> {
              log.atTrace()
                  .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                  .addKeyValue(
                      LOG_KEY_PLUGIN_TYPE,
                      plugin != null ? plugin.getClass().getSimpleName() : "null")
                  .addKeyValue("timeout", nodeTimeout.toMillis() + "ms")
                  .addKeyValue("bufferSize", bufferSize)
                  .log("Assembler strategy selected");
              return strategy.createAssembler(
                  node, plugin, nodeTimeout, nodeIndex, bufferSize, parentEdges);
            })
        .orElseGet(
            () -> {
              log.atWarn()
                  .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                  .addKeyValue(LOG_KEY_PLUGIN_TYPE, node.type())
                  .log("Unknown plugin type, creating no-op assembler");
              return context -> {};
            });
  }

  /**
   * Gets the buffer size for a node.
   *
   * @param node the node
   * @param plugin the plugin
   * @return the buffer size
   */
  private int getBufferSize(final WorkflowNode node, final Plugin plugin) {
    final Object bufferVal = node.config().get("bufferSize");
    final int result;
    if (bufferVal instanceof Number numValue && numValue.intValue() > 0) {
      result = numValue.intValue();
      log.atTrace()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .addKeyValue("bufferSize", result)
          .addKeyValue("source", "nodeConfig")
          .log("Using node-specific buffer size");
    } else {
      result = plugin != null ? plugin.getDefaultBufferSize() : BUFFER_SIZE;
      log.atTrace()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .addKeyValue("bufferSize", result)
          .addKeyValue("source", plugin != null ? "pluginDefault" : "systemDefault")
          .log("Using default buffer size");
    }
    return result;
  }

  /**
   * Gets the timeout for a node.
   *
   * @param node the node
   * @param plugin the plugin
   * @return the timeout duration
   */
  private Duration getNodeTimeout(final WorkflowNode node, final Plugin plugin) {
    final Object timeoutVal = node.config().get("timeoutSeconds");

    final Duration result;
    if (timeoutVal instanceof Number numValue && numValue.longValue() > 0) {
      result = Duration.ofSeconds(numValue.longValue());
      log.atTrace()
          .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
          .addKeyValue("timeoutMs", result.toMillis())
          .addKeyValue("source", "nodeConfig")
          .log("Using node-specific timeout");
    } else {
      @SuppressWarnings("PMD.LawOfDemeter")
      final Duration defaultTimeout = plugin != null ? plugin.getDefaultTimeout() : null;
      if (defaultTimeout != null) {
        result = defaultTimeout;
        log.atTrace()
            .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
            .addKeyValue("timeoutMs", result.toMillis())
            .addKeyValue("source", "pluginDefault")
            .log("Using plugin default timeout");
      } else {
        result = Duration.ofSeconds(REF_COUNT_TIMEOUT);
        log.atTrace()
            .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
            .addKeyValue("timeoutMs", result.toMillis())
            .addKeyValue("source", "systemDefault")
            .log("Using system default timeout");
      }
    }
    return result;
  }
}
