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

import com.infenia.jagratha.model.PreparedWorkflow;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowDefinition.Node;
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
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/** Orchestrator for executing reactive workflow DAGs. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings("PMD.ExcessiveImports")
public class WorkflowOrchestrator {

  private static final int BUFFER_SIZE = 1024;
  private static final long DEFAULT_TIMEOUT = 30L;
  private static final long GLOBAL_TIMEOUT = 300L;
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILURE = "FAILURE";
  private static final String STATUS_ERROR = "ERROR";
  private static final String DEFAULT_TASK_ID = "default";

  private final WorkflowRegistry registry;
  private final TaskTrackerService tracker;
  private final WorkflowValidator validator;

  /**
   * Validate and initialize all plugins in the workflow.
   *
   * @param def the workflow definition
   * @return a Mono that completes if preparation is successful
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
                            new IllegalArgumentException(
                                "Plugin not found for type: " + node.type()));
                      }
                      return plugin.initialize(node.config());
                    })
                .then())
        .then(
            Mono.fromCallable(
                () -> {
                  final List<Node> topologicalOrder =
                      PreparedWorkflow.computeTopologicalOrder(def, adjacencyList, parentsList);
                  return new PreparedWorkflow(
                      def, adjacencyList, parentsList, pluginCache, topologicalOrder);
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
   * Execute the workflow.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param executionId the unique execution identifier
   * @param prepared the prepared workflow
   * @param payload the initial trigger payload
   * @return a Mono that completes when all branches of the workflow have finished
   */
  public Mono<Void> execute(
      @SessionId final String sessionId,
      @WorkflowId final String workflowId,
      @NotBlank final String executionId,
      @NotNull @Valid final PreparedWorkflow prepared,
      @NotEmpty final Map<String, Object> payload) {

    return Mono.deferContextual(
            ctx -> {
              final String sId = ctx.get("sessionId");
              final String wId = ctx.get("workflowId");
              final String eId = ctx.get("executionId");
              final List<String> nodeIds =
                  prepared.definition().nodes().stream().map(Node::nodeId).toList();

              return tracker
                  .startWorkflow(eId, sId, wId, nodeIds)
                  .then(
                      Mono.defer(
                          () -> {
                            final Map<String, Flux<Message>> nodeStreams =
                                new ConcurrentHashMap<>();
                            final List<Mono<Void>> terminals = new ArrayList<>();

                            for (final Node node : prepared.topologicalOrder()) {
                              buildNodeIterative(
                                  eId, node, prepared, payload, nodeStreams, terminals);
                            }

                            return Flux.fromIterable(terminals)
                                .flatMapDelayError(m -> m, 256, 32)
                                .then(tracker.finishWorkflow(eId, STATUS_SUCCESS))
                                .onErrorResume(
                                    e ->
                                        tracker
                                            .finishWorkflow(eId, STATUS_ERROR)
                                            .then(Mono.error(e)));
                          }))
                  .timeout(Duration.ofSeconds(GLOBAL_TIMEOUT));
            })
        .contextWrite(
            Context.of(
                "sessionId", sessionId, "workflowId", workflowId, "executionId", executionId));
  }

  @SuppressWarnings("PMD.LawOfDemeter")
  private void buildNodeIterative(
      final String executionId,
      final Node node,
      final PreparedWorkflow prepared,
      final Map<String, Object> payload,
      final Map<String, Flux<Message>> nodeStreams,
      final List<Mono<Void>> terminals) {

    final List<Node> children = prepared.adjacencyList().get(node.nodeId());
    final WorkflowPlugin plugin = prepared.pluginCache().get(node.nodeId());
    final Duration nodeTimeout = getNodeTimeout(node);

    if (plugin.getCategory() == PluginCategory.TRIGGER) {
      final TriggerPlugin trigger = (TriggerPlugin) plugin;
      final Flux<Message> stream =
          tracker
              .updateTaskStatus(executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_RUNNING)
              .thenMany(trigger.start(node.config(), payload))
              .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()))
              .concatWith(
                  Mono.defer(
                      () ->
                          tracker
                              .updateTaskStatus(
                                  executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_SUCCESS)
                              .then(Mono.empty())))
              .onErrorResume(
                  e ->
                      tracker
                          .updateTaskStatus(
                              executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE)
                          .then(Mono.error(e)));

      nodeStreams.put(
          node.nodeId(),
          applyLoggingAndBroadcasting(executionId, node.nodeId(), stream, children.size()));
    } else {
      final Flux<Message> mergedInput =
          Flux.merge(
              prepared.definition().edges().stream()
                  .filter(edge -> edge.target().equals(node.nodeId()))
                  .map(
                      edge -> {
                        final Flux<Message> parentStream = nodeStreams.get(edge.source());
                        final Flux<Message> stampedStream =
                            parentStream.map(msg -> msg.withSourceNodeId(edge.source()));
                        if (edge.sourcePort() != null) {
                          return stampedStream.filter(
                              msg -> edge.sourcePort().equals(msg.sourcePort()));
                        }
                        return stampedStream;
                      })
                  .toList());

      if (plugin instanceof ProcessorPlugin processor) {
        final Flux<Message> stream =
            mergedInput
                .publish(
                    f ->
                        f.next()
                            .flatMapMany(
                                msg ->
                                    tracker
                                        .updateTaskStatus(
                                            executionId,
                                            node.nodeId(),
                                            DEFAULT_TASK_ID,
                                            STATUS_RUNNING)
                                        .thenMany(
                                            processor.process(
                                                Flux.concat(Mono.just(msg), f), node.config()))
                                        .concatWith(
                                            Mono.defer(
                                                () ->
                                                    tracker
                                                        .updateTaskStatus(
                                                            executionId,
                                                            node.nodeId(),
                                                            DEFAULT_TASK_ID,
                                                            STATUS_SUCCESS)
                                                        .then(Mono.empty()))))
                            .switchIfEmpty(
                                Mono.defer(
                                        () ->
                                            tracker.updateTaskStatus(
                                                executionId,
                                                node.nodeId(),
                                                DEFAULT_TASK_ID,
                                                "SKIPPED"))
                                    .thenMany(Flux.empty())))
                .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()))
                .onErrorResume(
                    e ->
                        tracker
                            .updateTaskStatus(
                                executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE)
                            .then(Mono.error(e)));

        nodeStreams.put(
            node.nodeId(),
            applyLoggingAndBroadcasting(executionId, node.nodeId(), stream, children.size()));
      } else if (plugin instanceof TerminalPlugin terminal) {
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
                                    tracker
                                        .updateTaskStatus(
                                            executionId,
                                            node.nodeId(),
                                            DEFAULT_TASK_ID,
                                            STATUS_RUNNING)
                                        .then(
                                            terminal.consume(
                                                Flux.concat(Mono.just(msg), f), node.config()))
                                        .then(
                                            tracker.updateTaskStatus(
                                                executionId,
                                                node.nodeId(),
                                                DEFAULT_TASK_ID,
                                                STATUS_SUCCESS)))
                            .switchIfEmpty(
                                Mono.defer(
                                    () ->
                                        tracker.updateTaskStatus(
                                            executionId,
                                            node.nodeId(),
                                            DEFAULT_TASK_ID,
                                            "SKIPPED"))))
                .contextWrite(ctx -> ctx.put("nodeId", node.nodeId()))
                .timeout(nodeTimeout)
                .then()
                .onErrorResume(
                    e ->
                        tracker
                            .updateTaskStatus(
                                executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE)
                            .then(Mono.error(e)));
        terminals.add(completion);
      }
    }
  }

  private Duration getNodeTimeout(final Node node) {
    final Object timeoutVal = node.config().get("timeoutSeconds");
    Duration timeout = Duration.ofSeconds(DEFAULT_TIMEOUT);
    if (timeoutVal instanceof Number numValue) {
      timeout = Duration.ofSeconds(numValue.longValue());
    }
    return timeout;
  }

  private Flux<Message> applyLoggingAndBroadcasting(
      final String executionId,
      final String nodeId,
      final Flux<Message> stream,
      final int childCount) {

    Flux<Message> processedStream =
        stream
            .onBackpressureBuffer(BUFFER_SIZE, BufferOverflowStrategy.ERROR)
            .log("Node-" + nodeId)
            .doOnNext(
                msg ->
                    Mono.fromRunnable(
                            () -> tracker.appendLog(executionId, String.valueOf(msg.payload())))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe());

    if (childCount > 0) {
      processedStream =
          processedStream.replay(1).refCount(childCount, Duration.ofSeconds(DEFAULT_TIMEOUT));
    } else {
      processedStream = processedStream.replay(1).autoConnect(0);
    }

    return processedStream;
  }
}
