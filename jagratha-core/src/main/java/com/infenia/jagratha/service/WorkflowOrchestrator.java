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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

/** Orchestrator for executing reactive workflow DAGs. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.LawOfDemeter",
  "PMD.TooManyMethods"
})
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
                      compileTemplate(def, parentsList, pluginCache, topologicalOrder);
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
      assemblers[i] = createNodeAssembler(def, node, parentsList, pluginCache, nodeToIndex);
    }

    final List<String> nodeIds = topologicalOrder.stream().map(Node::nodeId).toList();

    return (executionId, payload) ->
        Mono.deferContextual(
            ctx ->
                tracker
                    .startWorkflow(
                        executionId, ctx.get("sessionId"), ctx.get("workflowId"), nodeIds)
                    .then(
                        Mono.defer(
                            () ->
                                executeTemplate(
                                    executionId, nodeCount, assemblers, ctx.get("sessionId"))))
                    .contextWrite(c -> c.put("payload", payload)));
  }

  /**
   * Internal execution logic for a compiled template.
   *
   * @param executionId the execution ID
   * @param nodeCount the number of nodes in the workflow
   * @param assemblers the node assemblers
   * @param sessionId the session ID
   * @return a Mono that completes when execution is finished
   */
  private Mono<Void> executeTemplate(
      final String executionId,
      final int nodeCount,
      final NodeAssembler[] assemblers,
      final String sessionId) {

    @SuppressWarnings("unchecked")
    final Flux<Message>[] streams = new Flux[nodeCount];
    final List<Mono<Void>> terminals = new ArrayList<>();
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    for (final NodeAssembler assembler : assemblers) {
      assembler.assemble(executionId, streams, terminals, disposables, connectors);
    }

    final Mono<Long> timeoutMono =
        configService.getExecutionTimeout(sessionId).defaultIfEmpty(GLOBAL_TIMEOUT);

    return Mono.using(
        () -> disposables,
        d ->
            timeoutMono.flatMap(
                wfTimeout -> {
                  Flux<Void> terminalFlux =
                      Flux.fromIterable(terminals).flatMapDelayError(m -> m, 256, 32);
                  if (wfTimeout > 0) {
                    terminalFlux = terminalFlux.timeout(Duration.ofSeconds(wfTimeout));
                  }
                  return terminalFlux
                      .doOnSubscribe(
                          s -> {
                            for (int i = connectors.size() - 1; i >= 0; i--) {
                              connectors.get(i).run();
                            }
                          })
                      .then()
                      .doOnSuccess(
                          v -> tracker.emitWorkflowStatusEvent(executionId, STATUS_SUCCESS))
                      .onErrorResume(
                          e -> {
                            tracker.emitWorkflowStatusEvent(executionId, STATUS_ERROR);
                            return Mono.error(e);
                          });
                }),
        d -> {
          for (final Disposable disposable : d) {
            disposable.dispose();
          }
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
      final Map<String, List<Node>> parentsList,
      final Map<String, WorkflowPlugin> pluginCache,
      final Map<String, Integer> nodeToIndex) {

    final WorkflowPlugin plugin = pluginCache.get(node.nodeId());
    final Duration nodeTimeout = getNodeTimeout(node, plugin);
    final int bufferSize = getBufferSize(node);
    final boolean hasParents = !parentsList.get(node.nodeId()).isEmpty();
    final int nodeIndex = nodeToIndex.get(node.nodeId());

    final NodeAssembler result;
    if (plugin instanceof TriggerPlugin trigger
        && (plugin.getCategory() == PluginCategory.TRIGGER || !hasParents)) {
      result = createTriggerAssembler(node, trigger, nodeTimeout, nodeIndex, bufferSize);
    } else {
      final List<ParentEdgeInfo> parentEdges =
          def.edges().stream()
              .filter(e -> e.target().equals(node.nodeId()))
              .map(e -> new ParentEdgeInfo(nodeToIndex.get(e.source()), e.source(), e.sourcePort()))
              .toList();

      if (plugin instanceof ProcessorPlugin processor) {
        result =
            createProcessorAssembler(
                node, processor, nodeTimeout, nodeIndex, bufferSize, parentEdges);
      } else if (plugin instanceof TerminalPlugin terminal) {
        result = createTerminalAssembler(node, terminal, nodeTimeout, parentEdges);
      } else {
        result = (executionId, streams, terminals, disposables, connectors) -> {};
      }
    }

    return result;
  }

  private NodeAssembler createTriggerAssembler(
      final Node node,
      final TriggerPlugin trigger,
      final Duration timeout,
      final int index,
      final int bufferSize) {

    return (executionId, streams, terminals, disposables, connectors) -> {
      final Flux<Message> stream =
          trigger
              .start(node.config())
              .timeout(timeout)
              .doOnSubscribe(
                  s ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_RUNNING, Map.of()))
              .doOnComplete(
                  () ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_SUCCESS, Map.of()))
              .doOnError(
                  e ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE, Map.of()))
              .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()));
      streams[index] =
          applyLoggingAndBroadcasting(
              executionId, node.nodeId(), stream, bufferSize, disposables, connectors);
    };
  }

  private NodeAssembler createProcessorAssembler(
      final Node node,
      final ProcessorPlugin processor,
      final Duration timeout,
      final int index,
      final int bufferSize,
      final List<ParentEdgeInfo> parentEdges) {

    return (executionId, streams, terminals, disposables, connectors) -> {
      final Flux<Message> mergedInput = mergeParentStreams(streams, parentEdges);
      final Flux<Message> stream =
          processor
              .process(mergedInput, node.config())
              .timeout(timeout)
              .doOnSubscribe(
                  s ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_RUNNING, Map.of()))
              .doOnComplete(
                  () ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_SUCCESS, Map.of()))
              .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()))
              .doOnError(
                  e ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE, Map.of()));
      streams[index] =
          applyLoggingAndBroadcasting(
              executionId, node.nodeId(), stream, bufferSize, disposables, connectors);
    };
  }

  private NodeAssembler createTerminalAssembler(
      final Node node,
      final TerminalPlugin terminal,
      final Duration timeout,
      final List<ParentEdgeInfo> parentEdges) {

    return (executionId, streams, terminalsList, disposables, connectors) -> {
      final Flux<Message> mergedInput = mergeParentStreams(streams, parentEdges);
      final Flux<Message> inputToTerminal =
          mergedInput.transformDeferredContextual(
              (flux, ctx) -> {
                final ResultCollector collector = ctx.getOrDefault("resultCollector", null);
                return collector != null ? flux.doOnNext(collector::add) : flux;
              });
      final Mono<Void> completion =
          terminal
              .consume(inputToTerminal, node.config())
              .timeout(timeout)
              .doOnSubscribe(
                  s ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_RUNNING, Map.of()))
              .doOnSuccess(
                  v ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_SUCCESS, Map.of()))
              .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()))
              .doOnError(
                  e ->
                      tracker.emitTaskStatusEvent(
                          executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE, Map.of()))
              .then();
      terminalsList.add(completion);
    };
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
                  final Flux<Message> result;
                  if (edge.sourcePort() != null) {
                    result =
                        stampedStream.filter(msg -> edge.sourcePort().equals(msg.sourcePort()));
                  } else {
                    result = stampedStream;
                  }
                  return result;
                })
            .toList());
  }

  private record ParentEdgeInfo(int parentIndex, String sourceNodeId, String sourcePort) {}

  /**
   * Gets the buffer size for a node.
   *
   * @param node the node
   * @return the buffer size
   */
  private int getBufferSize(final Node node) {
    final Object bufferVal = node.config().get("bufferSize");
    final int result;
    if (bufferVal instanceof Number numValue && numValue.intValue() > 0) {
      result = numValue.intValue();
    } else {
      result = BUFFER_SIZE;
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
  private Duration getNodeTimeout(final Node node, final WorkflowPlugin plugin) {
    final Object timeoutVal =
        node.config().getOrDefault("timeoutSeconds", node.config().get("timeout"));

    final Duration result;
    if (timeoutVal instanceof Number numValue && numValue.longValue() > 0) {
      result = Duration.ofSeconds(numValue.longValue());
    } else {
      final Duration defaultTimeout = plugin != null ? plugin.getDefaultTimeout() : null;
      if (defaultTimeout != null) {
        result = defaultTimeout;
      } else {
        result = Duration.ofSeconds(REF_COUNT_TIMEOUT);
      }
    }
    return result;
  }

  /**
   * Applies logging and broadcasting (Sinks) to a stream.
   *
   * @param executionId the execution ID
   * @param nodeId the node ID
   * @param stream the stream to process
   * @param bufferSize the buffer size
   * @param disposables the list of disposables to manage resource lifecycle
   * @param connectors the list of tasks to connect upstreams to sinks
   * @return the processed stream
   */
  private Flux<Message> applyLoggingAndBroadcasting(
      final String executionId,
      final String nodeId,
      final Flux<Message> stream,
      final int bufferSize,
      final List<Disposable> disposables,
      final List<Runnable> connectors) {
    Flux<Message> logStream = stream;
    // 1. Conditional Reactor Logging: Only active if DEBUG level is set for this class
    if (log.isDebugEnabled()) {
      logStream = logStream.log("Node-" + nodeId);
    }
    final Flux<Message> processedStream =
        logStream.doOnNext(
            msg -> {
              if (log.isTraceEnabled()) { // Only capture payload strings at TRACE level
                tracker.emitLogEvent(executionId, String.valueOf(msg.payload()));
              }
            });

    final Sinks.Many<Message> sink =
        Sinks.many().multicast().onBackpressureBuffer(bufferSize, false);
    connectors.add(
        () ->
            disposables.add(
                processedStream.subscribe(
                    msg -> sink.emitNext(msg, Sinks.EmitFailureHandler.FAIL_FAST),
                    err -> sink.emitError(err, Sinks.EmitFailureHandler.FAIL_FAST),
                    () -> sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST))));

    return sink.asFlux();
  }
}
