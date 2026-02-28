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
package com.infenia.yukta.service;

import com.infenia.yukta.config.AppConfigService;
import com.infenia.yukta.model.NodeAssembler;
import com.infenia.yukta.model.PreparedWorkflow;
import com.infenia.yukta.model.WorkflowDefinition;
import com.infenia.yukta.model.WorkflowDefinition.Node;
import com.infenia.yukta.model.WorkflowTemplate;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.MessageStore;
import com.infenia.yukta.plugin.PluginCategory;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.ResultCollector;
import com.infenia.yukta.plugin.TerminalPlugin;
import com.infenia.yukta.plugin.TriggerPlugin;
import com.infenia.yukta.plugin.WorkflowPlugin;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.util.context.Context;

/** Orchestrator for executing reactive workflow DAGs. */
@Slf4j
@Service
@Validated
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.LawOfDemeter",
  "PMD.TooManyMethods",
  "PMD.LongVariable"
})
public class WorkflowOrchestrator {

  private static final int BUFFER_SIZE = 1024;
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      (signalType, emitResult) -> {
        if (emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
          // Yield carrier thread for 10 microseconds to allow concurrent emission to complete
          LockSupport.parkNanos(10_000);
          return true;
        }
        return false;
      };
  private static final long GLOBAL_TIMEOUT = 3600L;
  private static final long REF_COUNT_TIMEOUT = 30L;
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILURE = "FAILURE";
  private static final String STATUS_ERROR = "ERROR";
  private static final String DEFAULT_TASK_ID = "default";

  private static final String CTX_SESSION_ID = "sessionId";
  private static final String CTX_WORKFLOW_ID = "workflowId";
  private static final String CTX_EXECUTION_ID = "executionId";
  private static final String CTX_PAYLOAD = "payload";
  private static final String CTX_NODE_ID = "nodeId";

  private final WorkflowRegistry registry;
  private final TaskTrackerService tracker;
  private final WorkflowValidator validator;
  private final AppConfigService configService;
  private final MessageStore messageStore;
  private final Scheduler virtualThreadScheduler;

  /**
   * Constructs a new WorkflowOrchestrator.
   *
   * @param registry the workflow registry
   * @param tracker the task tracker service
   * @param validator the workflow validator
   * @param configService the app config service
   * @param messageStore the message store for auditing
   * @param virtualThreadScheduler the scheduler for virtual threads
   */
  @Autowired
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP2")
  public WorkflowOrchestrator(
      final WorkflowRegistry registry,
      final TaskTrackerService tracker,
      final WorkflowValidator validator,
      final AppConfigService configService,
      @Nullable final MessageStore messageStore,
      @Qualifier("virtualThreadScheduler") final Scheduler virtualThreadScheduler) {
    this.registry = registry;
    this.tracker = tracker;
    this.validator = validator;
    this.configService = configService;
    this.messageStore = messageStore;
    this.virtualThreadScheduler = virtualThreadScheduler;
  }

  /**
   * Prepares a workflow for execution.
   *
   * @param def the workflow definition
   * @return a Mono containing the prepared workflow
   */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  public Mono<PreparedWorkflow> prepareWorkflow(@NotNull @Valid final WorkflowDefinition def) {
    final Map<String, List<Node>> adjacencyList = new HashMap<>();
    final Map<String, List<Node>> parentsList = new HashMap<>();
    final Map<String, WorkflowPlugin> pluginCache = new HashMap<>();
    final Map<String, Node> nodeMap = new HashMap<>();

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
                CTX_SESSION_ID, sessionId,
                CTX_WORKFLOW_ID, workflowId,
                CTX_EXECUTION_ID, executionId));
  }

  /**
   * Compiles a workflow template for high-performance execution.
   *
   * @param def the workflow definition
   * @param parentsList map of nodeId to parent nodes
   * @param pluginCache map of nodeId to initialized plugin instances
   * @param topologicalOrder list of nodes in topological order
   * @return the compiled workflow template
   */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private WorkflowTemplate compileTemplate(
      final WorkflowDefinition def,
      final Map<String, List<Node>> parentsList,
      final Map<String, WorkflowPlugin> pluginCache,
      final List<Node> topologicalOrder) {

    final int nodeCount = topologicalOrder.size();
    final Map<String, Integer> nodeToIndex = new HashMap<>();
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
                        executionId, ctx.get(CTX_SESSION_ID), ctx.get(CTX_WORKFLOW_ID), nodeIds)
                    .then(
                        Mono.defer(
                            () ->
                                executeTemplate(
                                    executionId,
                                    payload,
                                    nodeCount,
                                    assemblers,
                                    ctx.get(CTX_SESSION_ID),
                                    ctx.get(CTX_WORKFLOW_ID))))
                    .contextWrite(c -> c.put(CTX_PAYLOAD, payload)));
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
   * @return a Mono that completes when execution is finished
   */
  private Mono<Void> executeTemplate(
      final String executionId,
      final Map<String, Object> payload,
      final int nodeCount,
      final NodeAssembler[] assemblers,
      final String sessionId,
      final String workflowId) {

    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[nodeCount];
    final List<Mono<Void>> terminals = new ArrayList<>(nodeCount);
    final List<Disposable> disposables = new ArrayList<>(nodeCount);
    final List<Runnable> connectors = new ArrayList<>(nodeCount);

    for (final NodeAssembler assembler : assemblers) {
      assembler.assemble(
          executionId, sessionId, workflowId, payload, streams, terminals, disposables, connectors);
    }

    final Mono<Long> timeoutMono =
        configService.getExecutionTimeout(sessionId).defaultIfEmpty(GLOBAL_TIMEOUT);

    return Mono.using(
        () -> disposables,
        d ->
            timeoutMono.flatMap(
                wfTimeout -> {
                  Mono<Void> terminalMono = Mono.whenDelayError(terminals);
                  if (wfTimeout > 0) {
                    terminalMono =
                        terminalMono.timeout(Duration.ofSeconds(wfTimeout), virtualThreadScheduler);
                  }
                  return terminalMono
                      .doOnSubscribe(
                          s -> {
                            for (int i = connectors.size() - 1; i >= 0; i--) {
                              connectors.get(i).run();
                            }
                          })
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
      final ParentEdgeInfo[] parentEdges =
          def.edges().stream()
              .filter(e -> e.target().equals(node.nodeId()))
              .map(e -> new ParentEdgeInfo(nodeToIndex.get(e.source()), e.source(), e.sourcePort()))
              .toArray(ParentEdgeInfo[]::new);

      if (plugin instanceof ProcessorPlugin processor) {
        result =
            createProcessorAssembler(
                node, processor, nodeTimeout, nodeIndex, bufferSize, parentEdges);
      } else if (plugin instanceof TerminalPlugin terminal) {
        result = createTerminalAssembler(node, terminal, nodeTimeout, parentEdges);
      } else {
        result = (execId, sessId, wfId, pld, strms, terms, disps, conns) -> {};
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

    return (execId, sessId, wfId, pld, strms, terms, disps, conns) -> {
      Flux<Message<?>> stream = trigger.start(node.config());
      if (trigger.isBlocking()) {
        stream = stream.subscribeOn(virtualThreadScheduler);
      }
      stream =
          stream
              .<Message<?>>flatMap(
                  msg ->
                      Mono.<Message<?>>just(msg)
                          .timeout(timeout, virtualThreadScheduler)
                          .onErrorMap(TimeoutException.class, e -> e),
                  bufferSize)
              .doOnSubscribe(
                  s ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_RUNNING,
                          Collections.emptyMap()))
              .doOnComplete(
                  () ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_SUCCESS,
                          Collections.emptyMap()))
              .doOnError(
                  e ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_FAILURE,
                          Collections.emptyMap()))
              .contextWrite(
                  ctx ->
                      ctx.put(CTX_NODE_ID, node.nodeId())
                          .put(CTX_PAYLOAD, pld)
                          .put(CTX_SESSION_ID, sessId)
                          .put(CTX_WORKFLOW_ID, wfId)
                          .put(CTX_EXECUTION_ID, execId));
      strms[index] =
          applyLoggingAndBroadcasting(execId, node.nodeId(), stream, bufferSize, disps, conns);
    };
  }

  @SuppressWarnings("PMD.UseVarargs")
  private NodeAssembler createProcessorAssembler(
      final Node node,
      final ProcessorPlugin processor,
      final Duration timeout,
      final int index,
      final int bufferSize,
      final ParentEdgeInfo[] parentEdges) {

    return (execId, sessId, wfId, pld, strms, terms, disps, conns) -> {
      final Flux<Message<?>> mergedInput = mergeParentStreams(strms, parentEdges);
      Flux<Message<?>> stream = processor.process(mergedInput, node.config());
      if (processor.isBlocking()) {
        stream = stream.subscribeOn(virtualThreadScheduler);
      }

      stream =
          stream
              .<Message<?>>flatMap(
                  msg ->
                      Mono.<Message<?>>just(msg)
                          .timeout(timeout, virtualThreadScheduler)
                          .onErrorMap(TimeoutException.class, e -> e),
                  bufferSize)
              .doOnSubscribe(
                  s ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_RUNNING,
                          Collections.emptyMap()))
              .doOnComplete(
                  () ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_SUCCESS,
                          Collections.emptyMap()))
              .contextWrite(
                  ctx ->
                      ctx.put(CTX_NODE_ID, node.nodeId())
                          .put(CTX_PAYLOAD, pld)
                          .put(CTX_SESSION_ID, sessId)
                          .put(CTX_WORKFLOW_ID, wfId)
                          .put(CTX_EXECUTION_ID, execId))
              .doOnError(
                  e ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_FAILURE,
                          Collections.emptyMap()));
      strms[index] =
          applyLoggingAndBroadcasting(execId, node.nodeId(), stream, bufferSize, disps, conns);
    };
  }

  @SuppressWarnings("PMD.UseVarargs")
  private NodeAssembler createTerminalAssembler(
      final Node node,
      final TerminalPlugin terminal,
      final Duration timeout,
      final ParentEdgeInfo[] parentEdges) {

    return (execId, sessId, wfId, pld, strms, terms, disps, conns) -> {
      final Flux<Message<?>> mergedInput = mergeParentStreams(strms, parentEdges);
      final Flux<Message<?>> inputToTerminal =
          mergedInput
              .<Message<?>>flatMap(
                  msg ->
                      Mono.<Message<?>>just(msg)
                          .timeout(timeout, virtualThreadScheduler)
                          .onErrorMap(TimeoutException.class, e -> e),
                  BUFFER_SIZE)
              .transformDeferredContextual(
                  (flux, ctx) -> {
                    final ResultCollector collector = ctx.getOrDefault("resultCollector", null);
                    return collector != null ? flux.doOnNext(collector::add) : flux;
                  });
      Mono<Void> completion = terminal.consume(inputToTerminal, node.config());
      if (terminal.isBlocking()) {
        completion = completion.subscribeOn(virtualThreadScheduler);
      }

      completion =
          completion
              .doOnSubscribe(
                  s ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_RUNNING,
                          Collections.emptyMap()))
              .doOnSuccess(
                  v ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_SUCCESS,
                          Collections.emptyMap()))
              .contextWrite(
                  ctx ->
                      ctx.put(CTX_NODE_ID, node.nodeId())
                          .put(CTX_PAYLOAD, pld)
                          .put(CTX_SESSION_ID, sessId)
                          .put(CTX_WORKFLOW_ID, wfId)
                          .put(CTX_EXECUTION_ID, execId))
              .doOnError(
                  e ->
                      tracker.emitTaskStatusEvent(
                          execId,
                          node.nodeId(),
                          DEFAULT_TASK_ID,
                          STATUS_FAILURE,
                          Collections.emptyMap()))
              .then();
      terms.add(completion);
    };
  }

  /**
   * Merges parent streams.
   *
   * @param streams the array of streams
   * @param parentEdges the parent edges
   * @return the merged stream
   */
  @SuppressWarnings({"PMD.UseVarargs", "PMD.OnlyOneReturn", "PMD.AvoidLiteralsInIfCondition"})
  private Flux<Message<?>> mergeParentStreams(
      final Flux<Message<?>>[] streams, final ParentEdgeInfo[] parentEdges) {
    if (parentEdges.length == 0) {
      return Flux.empty();
    }
    if (parentEdges.length == 1) {
      return applyEdgeRouting(streams, parentEdges[0]);
    }

    final List<Flux<Message<?>>> parentFluxes = new ArrayList<>(parentEdges.length);
    for (final ParentEdgeInfo edge : parentEdges) {
      parentFluxes.add(applyEdgeRouting(streams, edge));
    }
    return Flux.merge(parentFluxes);
  }

  private Flux<Message<?>> applyEdgeRouting(
      final Flux<Message<?>>[] streams, final ParentEdgeInfo edge) {
    Flux<Message<?>> stream =
        streams[edge.parentIndex()].map(msg -> msg.withSourceNodeId(edge.sourceNodeId()));

    if (edge.sourcePort() != null) {
      stream = stream.filter(msg -> edge.sourcePort().equals(msg.getSourcePort()));
    }
    return stream;
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
  private Flux<Message<?>> applyLoggingAndBroadcasting(
      final String executionId,
      final String nodeId,
      final Flux<Message<?>> stream,
      final int bufferSize,
      final List<Disposable> disposables,
      final List<Runnable> connectors) {
    final Flux<Message<?>> historiedStream = stream.map(msg -> msg.withAddedHistory(nodeId));
    final Flux<Message<?>> processedStream = getMessageFlux(executionId, nodeId, historiedStream);

    final Sinks.Many<Message<?>> sink = Sinks.many().multicast().onBackpressureBuffer(bufferSize);

    connectors.add(
        () ->
            disposables.add(
                processedStream.subscribe(
                    msg -> sink.emitNext(msg, RETRY_HANDLER),
                    err -> sink.emitError(err, RETRY_HANDLER),
                    () -> sink.emitComplete(RETRY_HANDLER))));

    return sink.asFlux();
  }

  private Flux<Message<?>> getMessageFlux(
      final String executionId, final String nodeId, final Flux<Message<?>> stream) {
    Flux<Message<?>> logStream = stream;
    // 1. Conditional Reactor Logging: Only active if DEBUG level is set for this class
    if (log.isDebugEnabled()) {
      logStream = logStream.log("Node-" + nodeId);
    }

    // 2. Wire Tap: Send to MessageStore if available
    if (messageStore != null) {
      logStream = logStream.flatMap(msg -> messageStore.store(msg).thenReturn(msg));
    }

    final Flux<Message<?>> processedStream;
    if (log.isTraceEnabled()) {
      processedStream =
          logStream.doOnNext(
              msg -> tracker.emitLogEvent(executionId, String.valueOf(msg.getPayload())));
    } else {
      processedStream = logStream;
    }
    return processedStream;
  }
}
