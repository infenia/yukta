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
package com.infenia.jagratha.service;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.NodeAssembler;
import com.infenia.jagratha.model.PreparedWorkflow;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowDefinition.Node;
import com.infenia.jagratha.model.WorkflowTemplate;
import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.PluginCategory;
import com.infenia.jagratha.plugin.ProcessorPlugin;
import com.infenia.jagratha.plugin.ResultCollector;
import com.infenia.jagratha.plugin.TerminalPlugin;
import com.infenia.jagratha.plugin.TriggerPlugin;
import com.infenia.jagratha.plugin.WorkflowPlugin;
import com.infenia.jagratha.validation.SessionId;
import com.infenia.jagratha.validation.WorkflowId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/** Orchestrator for executing reactive workflow DAGs. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects", "PMD.LawOfDemeter"})
public class WorkflowOrchestrator {

  private static final int BUFFER_SIZE = 1024;
  private static final long GLOBAL_TIMEOUT = 3600L;
  private static final long REF_COUNT_TIMEOUT = 30L;
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILURE = "FAILURE";
  private static final String STATUS_ERROR = "ERROR";
  private static final String DEFAULT_TASK_ID = "default";

  private final WorkflowRegistry registry;
  private final TaskTrackerService tracker;
  private final WorkflowValidator validator;
  private final AppConfigService configService;

  /**
   * Prepares a workflow for execution.
   *
   * @param def the workflow definition
   * @return a Mono containing the prepared workflow
   */
  public Mono<PreparedWorkflow> prepareWorkflow(@NotNull @Valid final WorkflowDefinition def) {
    final Map<String, List<Node>> adjacencyList = new ConcurrentHashMap<>();
    final Map<String, List<Node>> parentsList = new ConcurrentHashMap<>();
    final Map<String, WorkflowPlugin> pluginCache = new ConcurrentHashMap<>();
    final Map<String, Node> nodeMap = new ConcurrentHashMap<>();

    def.nodes().forEach(node -> nodeMap.put(node.nodeId(), node));
    def.nodes()
        .forEach(
            node -> {
              adjacencyList.put(node.nodeId(), new ArrayList<>());
              parentsList.put(node.nodeId(), new ArrayList<>());
              final WorkflowPlugin plugin = registry.get(node.type());
              if (plugin != null) {
                pluginCache.put(node.nodeId(), plugin);
              }
            });

    def.edges()
        .forEach(
            edge -> {
              adjacencyList.get(edge.source()).add(nodeMap.get(edge.target()));
              parentsList.get(edge.target()).add(nodeMap.get(edge.source()));
            });

    return validator
        .validate(def)
        .then(
            Flux.fromIterable(def.nodes())
                .flatMap(
                    node -> {
                      final WorkflowPlugin plugin = pluginCache.get(node.nodeId());
                      if (plugin == null) {
                        return Mono.error(
                            new IllegalArgumentException("Plugin not found: " + node.type()));
                      }
                      return plugin.initialize(node.config());
                    })
                .then())
        .then(
            Mono.fromCallable(
                () -> {
                  final List<Node> topologicalOrder =
                      PreparedWorkflow.computeTopologicalOrder(def, adjacencyList, parentsList);
                  final WorkflowTemplate template =
                      compileTemplate(
                          def, adjacencyList, parentsList, pluginCache, topologicalOrder);
                  return new PreparedWorkflow(
                      def, adjacencyList, parentsList, pluginCache, topologicalOrder, template);
                }))
        .onErrorResume(
            e ->
                Flux.fromIterable(def.nodes())
                    .flatMap(
                        node -> {
                          final WorkflowPlugin plugin = pluginCache.get(node.nodeId());
                          if (plugin != null) {
                            final Mono<Void> shutdown = plugin.shutdown(node.config());
                            return (shutdown != null ? shutdown : Mono.<Void>empty())
                                .onErrorResume(ex -> Mono.empty());
                          }
                          return Mono.empty();
                        })
                    .then(Mono.error(e)));
  }

  /**
   * Executes a workflow.
   *
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @param executionId the execution ID
   * @param prepared the prepared workflow
   * @param payload the initial payload
   * @return a Mono that completes when the workflow execution is finished
   */
  public Mono<Void> execute(
      @SessionId final String sessionId,
      @WorkflowId final String workflowId,
      @NotBlank final String executionId,
      @NotNull @Valid final PreparedWorkflow prepared,
      @NotEmpty final Map<String, Object> payload) {

    return prepared
        .template()
        .instantiate(executionId, payload)
        .contextWrite(
            Context.of(
                "sessionId", sessionId, "workflowId", workflowId, "executionId", executionId));
  }

  /**
   * Compiles a workflow template for high-performance execution.
   *
   * @param def the workflow definition
   * @param adjacencyList map of nodeId to child nodes
   * @param parentsList map of nodeId to parent nodes
   * @param pluginCache map of nodeId to initialized plugin instances
   * @param topologicalOrder list of nodes in topological order
   * @return the compiled workflow template
   */
  private WorkflowTemplate compileTemplate(
      final WorkflowDefinition def,
      final Map<String, List<Node>> adjacencyList,
      final Map<String, List<Node>> parentsList,
      final Map<String, WorkflowPlugin> pluginCache,
      final List<Node> topologicalOrder) {

    final int nodeCount = topologicalOrder.size();
    final Map<String, Integer> nodeToIndex = new ConcurrentHashMap<>();
    for (int i = 0; i < nodeCount; i++) {
      nodeToIndex.put(topologicalOrder.get(i).nodeId(), i);
    }

    final NodeAssembler[] assemblers = new NodeAssembler[nodeCount];
    for (int i = 0; i < nodeCount; i++) {
      final Node node = topologicalOrder.get(i);
      assemblers[i] =
          createNodeAssembler(def, node, adjacencyList, parentsList, pluginCache, nodeToIndex);
    }

    final List<String> nodeIds = topologicalOrder.stream().map(Node::nodeId).toList();

    return (executionId, payload) ->
        Mono.deferContextual(
            ctx -> {
              final String sId = ctx.get("sessionId");
              final String wId = ctx.get("workflowId");

              return tracker
                  .startWorkflow(executionId, sId, wId, nodeIds)
                  .then(
                      Mono.defer(
                          () -> {
                            @SuppressWarnings("unchecked")
                            final Flux<Message>[] streams = new Flux[nodeCount];
                            final List<Mono<Void>> terminals = new ArrayList<>();

                            for (final NodeAssembler assembler : assemblers) {
                              assembler.assemble(executionId, streams, terminals);
                            }

                            final Mono<Long> timeoutMono =
                                configService
                                    .getExecutionTimeout(sId)
                                    .defaultIfEmpty(GLOBAL_TIMEOUT);

                            return timeoutMono.flatMap(
                                wfTimeout -> {
                                  Flux<Void> terminalFlux =
                                      Flux.fromIterable(terminals)
                                          .flatMapDelayError(m -> m, 256, 32);
                                  if (wfTimeout > 0) {
                                    terminalFlux =
                                        terminalFlux.timeout(Duration.ofSeconds(wfTimeout));
                                  }
                                  return terminalFlux
                                      .then()
                                      .doOnSuccess(
                                          v ->
                                              tracker.emitWorkflowStatusEvent(
                                                  executionId, STATUS_SUCCESS))
                                      .onErrorResume(
                                          e -> {
                                            tracker.emitWorkflowStatusEvent(
                                                executionId, STATUS_ERROR);
                                            return Mono.error(e);
                                          });
                                });
                          }))
                  .contextWrite(c -> c.put("payload", payload));
            });
  }

  /**
   * Creates a NodeAssembler for a specific node.
   *
   * @param def the workflow definition
   * @param node the node to assemble
   * @param adjacencyList map of nodeId to child nodes
   * @param parentsList map of nodeId to parent nodes
   * @param pluginCache map of nodeId to initialized plugin instances
   * @param nodeToIndex map of nodeId to stream array index
   * @return the NodeAssembler
   */
  private NodeAssembler createNodeAssembler(
      final WorkflowDefinition def,
      final Node node,
      final Map<String, List<Node>> adjacencyList,
      final Map<String, List<Node>> parentsList,
      final Map<String, WorkflowPlugin> pluginCache,
      final Map<String, Integer> nodeToIndex) {

    final List<Node> children = adjacencyList.get(node.nodeId());
    final int childCount = children != null ? children.size() : 0;
    final WorkflowPlugin plugin = pluginCache.get(node.nodeId());
    final Duration nodeTimeout = getNodeTimeout(node, plugin);
    final boolean hasParents = !parentsList.get(node.nodeId()).isEmpty();
    final int nodeIndex = nodeToIndex.get(node.nodeId());

    boolean treatAsTrigger = false;
    if (plugin instanceof TriggerPlugin) {
      final PluginCategory category = plugin.getCategory();
      if (category == PluginCategory.TRIGGER || !hasParents) {
        treatAsTrigger = true;
      }
    }

    NodeAssembler resultAssembler = (executionId, streams, terminals) -> {};

    if (treatAsTrigger) {
      final TriggerPlugin trigger = (TriggerPlugin) plugin;
      resultAssembler =
          (executionId, streams, terminals) -> {
            final Flux<Message> stream =
                trigger
                    .start(node.config())
                    .timeout(nodeTimeout)
                    .doOnSubscribe(
                        s ->
                            tracker.emitTaskStatusEvent(
                                executionId,
                                node.nodeId(),
                                DEFAULT_TASK_ID,
                                STATUS_RUNNING,
                                Map.of()))
                    .doOnComplete(
                        () ->
                            tracker.emitTaskStatusEvent(
                                executionId,
                                node.nodeId(),
                                DEFAULT_TASK_ID,
                                STATUS_SUCCESS,
                                Map.of()))
                    .doOnError(
                        e ->
                            tracker.emitTaskStatusEvent(
                                executionId,
                                node.nodeId(),
                                DEFAULT_TASK_ID,
                                STATUS_FAILURE,
                                Map.of()))
                    .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()));
            streams[nodeIndex] =
                applyLoggingAndBroadcasting(executionId, node.nodeId(), stream, childCount);
          };
    } else {
      final List<ParentEdgeInfo> parentEdges =
          def.edges().stream()
              .filter(e -> e.target().equals(node.nodeId()))
              .map(e -> new ParentEdgeInfo(nodeToIndex.get(e.source()), e.source(), e.sourcePort()))
              .toList();

      if (plugin instanceof ProcessorPlugin processor) {
        resultAssembler =
            (executionId, streams, terminals) -> {
              final Flux<Message> mergedInput = mergeParentStreams(streams, parentEdges);
              final Flux<Message> stream =
                  mergedInput
                      .publish(
                          f ->
                              f.next()
                                  .flatMapMany(
                                      msg ->
                                          processor
                                              .process(
                                                  Flux.concat(Mono.just(msg), f), node.config())
                                              .timeout(nodeTimeout)
                                              .doOnSubscribe(
                                                  s ->
                                                      tracker.emitTaskStatusEvent(
                                                          executionId,
                                                          node.nodeId(),
                                                          DEFAULT_TASK_ID,
                                                          STATUS_RUNNING,
                                                          Map.of()))
                                              .doOnComplete(
                                                  () ->
                                                      tracker.emitTaskStatusEvent(
                                                          executionId,
                                                          node.nodeId(),
                                                          DEFAULT_TASK_ID,
                                                          STATUS_SUCCESS,
                                                          Map.of())))
                                  .switchIfEmpty(
                                      Mono.fromRunnable(
                                              () ->
                                                  tracker.emitTaskStatusEvent(
                                                      executionId,
                                                      node.nodeId(),
                                                      DEFAULT_TASK_ID,
                                                      "SKIPPED",
                                                      Map.of()))
                                          .thenMany(Flux.empty())))
                      .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()))
                      .doOnError(
                          e ->
                              tracker.emitTaskStatusEvent(
                                  executionId,
                                  node.nodeId(),
                                  DEFAULT_TASK_ID,
                                  STATUS_FAILURE,
                                  Map.of()));
              streams[nodeIndex] =
                  applyLoggingAndBroadcasting(executionId, node.nodeId(), stream, childCount);
            };
      } else if (plugin instanceof TerminalPlugin terminal) {
        resultAssembler =
            (executionId, streams, terminalsList) -> {
              final Flux<Message> mergedInput = mergeParentStreams(streams, parentEdges);
              final Flux<Message> inputToTerminal =
                  mergedInput.concatMap(
                      msg ->
                          Mono.deferContextual(
                              ctx -> {
                                ctx.<ResultCollector>getOrEmpty("resultCollector")
                                    .ifPresent(collector -> collector.add(msg));
                                return Mono.just(msg);
                              }));
              final Mono<Void> completion =
                  inputToTerminal
                      .publish(
                          f ->
                              f.next()
                                  .flatMap(
                                      msg ->
                                          terminal
                                              .consume(
                                                  Flux.concat(Mono.just(msg), f), node.config())
                                              .timeout(nodeTimeout)
                                              .doOnSubscribe(
                                                  s ->
                                                      tracker.emitTaskStatusEvent(
                                                          executionId,
                                                          node.nodeId(),
                                                          DEFAULT_TASK_ID,
                                                          STATUS_RUNNING,
                                                          Map.of()))
                                              .doOnSuccess(
                                                  v ->
                                                      tracker.emitTaskStatusEvent(
                                                          executionId,
                                                          node.nodeId(),
                                                          DEFAULT_TASK_ID,
                                                          STATUS_SUCCESS,
                                                          Map.of())))
                                  .switchIfEmpty(
                                      Mono.fromRunnable(
                                          () ->
                                              tracker.emitTaskStatusEvent(
                                                  executionId,
                                                  node.nodeId(),
                                                  DEFAULT_TASK_ID,
                                                  "SKIPPED",
                                                  Map.of()))))
                      .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()))
                      .doOnError(
                          e ->
                              tracker.emitTaskStatusEvent(
                                  executionId,
                                  node.nodeId(),
                                  DEFAULT_TASK_ID,
                                  STATUS_FAILURE,
                                  Map.of()))
                      .then();
              terminalsList.add(completion);
            };
      }
    }

    return resultAssembler;
  }

  private Flux<Message> mergeParentStreams(
      final Flux<Message>[] streams, final List<ParentEdgeInfo> parentEdges) {
    return Flux.merge(
        parentEdges.stream()
            .map(
                edge -> {
                  final Flux<Message> parentStream = streams[edge.parentIndex()];
                  final Flux<Message> stampedStream =
                      parentStream.map(msg -> msg.withSourceNodeId(edge.sourceNodeId()));
                  if (edge.sourcePort() != null) {
                    return stampedStream.filter(msg -> edge.sourcePort().equals(msg.sourcePort()));
                  }
                  return stampedStream;
                })
            .toList());
  }

  private record ParentEdgeInfo(int parentIndex, String sourceNodeId, String sourcePort) {}

  /**
   * Gets the timeout for a node.
   *
   * @param node the node
   * @param plugin the plugin
   * @return the timeout duration
   */
  private Duration getNodeTimeout(final Node node, final WorkflowPlugin plugin) {
    final Object timeoutVal =
        node.config().getOrDefault("timeoutSeconds", node.config().get("timeout"));
    final Duration finalTimeout;
    if (timeoutVal instanceof Number numValue && numValue.longValue() > 0) {
      finalTimeout = Duration.ofSeconds(numValue.longValue());
    } else {
      Duration defaultTimeout = null;
      if (plugin != null) {
        defaultTimeout = plugin.getDefaultTimeout();
      }

      if (defaultTimeout != null) {
        finalTimeout = defaultTimeout;
      } else {
        finalTimeout = Duration.ofSeconds(REF_COUNT_TIMEOUT);
      }
    }
    return finalTimeout;
  }

  /**
   * Applies logging and broadcasting (replay/refCount) to a stream.
   *
   * @param executionId the execution ID
   * @param nodeId the node ID
   * @param stream the stream to process
   * @param childCount the number of children
   * @return the processed stream
   */
  private Flux<Message> applyLoggingAndBroadcasting(
      final String executionId,
      final String nodeId,
      final Flux<Message> stream,
      final int childCount) {
    Flux<Message> processedStream =
        stream.onBackpressureBuffer(BUFFER_SIZE, BufferOverflowStrategy.ERROR);
    // 1. Conditional Reactor Logging: Only active if DEBUG level is set for this class
    if (log.isDebugEnabled()) {
      processedStream = processedStream.log("Node-" + nodeId);
    }
    processedStream =
        processedStream.doOnNext(
            msg -> {
              if (log.isTraceEnabled()) { // Only capture payload strings at TRACE level
                tracker.emitLogEvent(executionId, String.valueOf(msg.payload()));
              }
            });
    if (childCount > 0) {
      processedStream =
          processedStream.replay(1).refCount(childCount, Duration.ofSeconds(REF_COUNT_TIMEOUT));
    } else {
      processedStream = processedStream.replay(1).autoConnect(0);
    }
    return processedStream;
  }
}
