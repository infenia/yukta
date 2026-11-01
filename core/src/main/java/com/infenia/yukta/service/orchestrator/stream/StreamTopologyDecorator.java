// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.orchestrator.assembly.ExecutionContextBuilder;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Decorates reactive message streams with topology-aware routing, logging, and checkpointing.
 *
 * <p>Encapsulates complex Flux manipulation for:
 *
 * <ul>
 *   <li>Merging parent streams with edge-based routing and filtering
 *   <li>Applying logging, checkpoint operators, and task tracking
 *   <li>Broadcasting messages to downstream subscribers via a backpressure-aware hot flux
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamTopologyDecorator {

  /** Most messages written to the checkpoint store in one call. */
  private static final int CHECKPOINT_BATCH_SIZE = 64;

  /** Longest, in milliseconds, a message waits for its batch to fill. */
  private static final long CHECKPOINT_BATCH_WAIT_MILLIS = 20;

  /** Constant for single parent node. */
  private static final int SINGLE_PARENT = 1;

  /** The task tracker service for tracking task execution. */
  private final TaskTrackerService tracker;

  /** The node checkpoint store for persisting node state. */
  private final NodeCheckpointStore checkpointStore;

  /** Maximum number of payload characters echoed to the live log per message. */
  private static final int MAX_LOG_PAYLOAD_SIZE = 2_048;

  /**
   * Merges parent node streams, applying edge-based routing and port filtering.
   *
   * <p>Routes incoming messages from parent nodes based on edge metadata (source node ID and
   * optional port). Returns empty flux if no parents.
   *
   * @param streams array of all node Flux streams indexed by their topological position
   * @param parentEdges edge metadata describing parent connections
   * @return merged Flux with routed and filtered messages
   */
  public Flux<Message<?>> mergeParentStreams(
      final Flux<Message<?>>[] streams, final ParentEdgeInfo... parentEdges) {
    return mergeParentStreams(NodePolicy.JoinRule.ALL_SUCCESS, streams, parentEdges);
  }

  /**
   * Merges parent node streams under a join rule.
   *
   * <p>With {@code ONE_SUCCESS} a failing parent is tolerated, but the merged stream fails with the
   * first parent error when every parent failed.
   *
   * @param rule the node's join rule
   * @param streams array of all node Flux streams indexed by their topological position
   * @param parentEdges edge metadata describing parent connections
   * @return merged Flux with routed and filtered messages
   */
  public Flux<Message<?>> mergeParentStreams(
      final NodePolicy.JoinRule rule,
      final Flux<Message<?>>[] streams,
      final ParentEdgeInfo... parentEdges) {
    log.atDebug().setMessage("Merging {} parent streams").addArgument(parentEdges.length).log();

    if (parentEdges.length == 0) {
      log.atDebug().setMessage("No parent streams to merge, returning empty flux").log();
      return Flux.empty();
    }
    final boolean oneSuccess = rule == NodePolicy.JoinRule.ONE_SUCCESS;
    // Registered now, at assembly, so every node knows its subscriber count before any connector
    // runs. Counting per subscription would let a connector drain a node whose child has not
    // subscribed yet.
    for (final ParentEdgeInfo edge : parentEdges) {
      if (streams[edge.parentIndex()] instanceof NodeBroadcast broadcast) {
        broadcast.expectSubscriber();
      }
    }
    // The failure counts belong to one subscription, so a resubscription starts from zero.
    return Flux.defer(
        () -> {
          final AtomicInteger failed = new AtomicInteger();
          final AtomicReference<Throwable> firstError = new AtomicReference<>();
          final List<Flux<Message<?>>> parentFluxes = new ArrayList<>(parentEdges.length);
          for (final ParentEdgeInfo edge : parentEdges) {
            parentFluxes.add(
                routeEdge(
                    streams,
                    edge,
                    error -> {
                      if (oneSuccess) {
                        failed.incrementAndGet();
                        firstError.compareAndSet(null, error);
                      }
                    }));
          }
          return mergeRouted(parentFluxes, oneSuccess, failed, firstError);
        });
  }

  private Flux<Message<?>> mergeRouted(
      final List<Flux<Message<?>>> parentFluxes,
      final boolean oneSuccess,
      final AtomicInteger failed,
      final AtomicReference<Throwable> firstError) {
    log.atDebug()
        .setMessage("Parent streams merged: {} streams")
        .addArgument(parentFluxes.size())
        .log();
    final Flux<Message<?>> merged =
        parentFluxes.size() == SINGLE_PARENT ? parentFluxes.getFirst() : Flux.merge(parentFluxes);
    return oneSuccess
        ? merged.concatWith(
            Mono.defer(
                () ->
                    failed.get() == parentFluxes.size()
                        ? Mono.error(firstError.get())
                        : Mono.empty()))
        : merged;
  }

  /**
   * Routes a single parent stream, filtering by port if specified.
   *
   * <p>Applies source node ID mapping and optional port-based filtering. Returns the routed stream.
   *
   * @param streams array of all node Flux streams
   * @param edge edge metadata (parent index, source node ID, source port)
   * @return routed Flux with messages mapped to source node
   */
  public Flux<Message<?>> applyEdgeRouting(
      final Flux<Message<?>>[] streams, final ParentEdgeInfo edge) {
    // Counts the edge as a future subscriber of its parent's shared output.
    if (streams[edge.parentIndex()] instanceof NodeBroadcast broadcast) {
      broadcast.expectSubscriber();
    }
    return routeEdge(streams, edge, _ -> {});
  }

  private Flux<Message<?>> routeEdge(
      final Flux<Message<?>>[] streams,
      final ParentEdgeInfo edge,
      final Consumer<Throwable> onTolerated) {
    log.atDebug()
        .setMessage("Applying edge routing - parent index: {}, source node: {}, source port: {}")
        .addArgument(edge.parentIndex())
        .addArgument(edge.sourceNodeId())
        .addArgument(edge.sourcePort())
        .log();

    final Flux<Message<?>> parent = streams[edge.parentIndex()];
    // Siblings share the parent's message; a copy per edge keeps one branch's edits to the payload
    // from showing up in another.
    Flux<Message<?>> stream =
        parent.transformDeferredContextual(
            (flux, ctx) ->
                flux.map(
                    msg -> {
                      final Message<?> own =
                          EngineOwned.reassert(
                                  parent instanceof NodeBroadcast broadcast && broadcast.isShared()
                                      ? PayloadCopy.copyOf(msg)
                                      : msg)
                              .withSourceNodeId(edge.sourceNodeId());
                      // The run's workflow id, so a plugin cannot claim another workflow's.
                      return ctx.hasKey(ExecutionContextBuilder.CTX_WORKFLOW_ID)
                          ? own.withWorkflowId(ctx.get(ExecutionContextBuilder.CTX_WORKFLOW_ID))
                          : own;
                    }));

    final String sourcePort = edge.sourcePort();
    if (NodePolicy.ALL_PORTS.equals(sourcePort)) {
      // Every port but the error port; like a named port it then starts the child at "default"
      stream =
          stream
              .filter(msg -> !NodePolicy.ERROR_PORT.equals(msg.sourcePort()))
              .map(msg -> msg.withSourcePort(NodePolicy.DEFAULT_PORT));
    } else if (sourcePort != null) {
      log.atDebug().setMessage("Filtering by source port: {}").addArgument(sourcePort).log();
      stream = stream.filter(msg -> sourcePort.equals(msg.sourcePort()));
      if (!NodePolicy.ERROR_PORT.equals(sourcePort)) {
        // The port only picks the edge. A plain node copies its input's port onto its output, so
        // without this reset it would hand "failure" on to its own "default" edges and the chain
        // behind a routed node would silently end.
        stream = stream.map(msg -> msg.withSourcePort(NodePolicy.DEFAULT_PORT));
      }
    } else {
      // Dead letters (onError: ROUTE) belong to the error port only, not to the normal edges.
      stream = stream.filter(msg -> !NodePolicy.ERROR_PORT.equals(msg.sourcePort()));
    }
    if (edge.tolerateFailure()) {
      stream =
          stream.onErrorResume(
              error -> {
                onTolerated.accept(error);
                log.atWarn()
                    .addKeyValue("sourceNodeId", edge.sourceNodeId())
                    .setCause(error)
                    .log("Parent failed; continuing without it (trigger != ALL_SUCCESS)");
                return Flux.empty();
              });
    }
    return stream;
  }

  /**
   * Applies logging, checkpointing, and task tracking, then broadcasts to downstream subscribers.
   *
   * <p>Decorates stream with:
   *
   * <ul>
   *   <li>Reactor logging (if DEBUG enabled)
   *   <li>Checkpoint save to NodeCheckpointStore
   *   <li>Task tracker log events (if TRACE enabled)
   * </ul>
   *
   * <p>Broadcasts via {@code publish(bufferSize)}: upstream is requested at the pace of the slowest
   * downstream subscriber, and operators are not replayed per subscriber.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param stream the incoming message stream
   * @param bufferSize prefetch used by the shared upstream subscription
   * @param disposables lifecycle management for subscriptions
   * @param connectors deferred subscription tasks (executed during assembly finalization)
   * @return shared Flux for downstream consumption; connected by the registered connector
   */
  public Flux<Message<?>> applyLoggingAndBroadcasting(
      final String executionId,
      final String nodeId,
      final Flux<Message<?>> stream,
      final int bufferSize,
      final List<Disposable> disposables,
      final List<Runnable> connectors) {
    return applyLoggingAndBroadcasting(
        executionId, nodeId, stream, bufferSize, disposables, connectors, () -> false);
  }

  /**
   * Like {@link #applyLoggingAndBroadcasting(String, String, Flux, int, List, List)}, for a node
   * that may write its log lines live. Its output message is then not echoed to the log again.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param stream the incoming message stream
   * @param bufferSize prefetch used by the shared upstream subscription
   * @param disposables lifecycle management for subscriptions
   * @param connectors deferred subscription tasks (executed during assembly finalization)
   * @param loggedLive tells whether the node has written its output to the log live
   * @return shared Flux for downstream consumption; connected by the registered connector
   */
  public Flux<Message<?>> applyLoggingAndBroadcasting(
      final String executionId,
      final String nodeId,
      final Flux<Message<?>> stream,
      final int bufferSize,
      final List<Disposable> disposables,
      final List<Runnable> connectors,
      final BooleanSupplier loggedLive) {
    return applyLoggingAndBroadcasting(
        executionId,
        nodeId,
        stream,
        bufferSize,
        disposables,
        connectors,
        loggedLive,
        NodePolicy.Checkpoint.ALL);
  }

  /**
   * Like the overload with {@code loggedLive}, with the node's {@code engine.checkpoint} mode.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param stream the incoming message stream
   * @param bufferSize prefetch used by the shared upstream subscription
   * @param disposables lifecycle management for subscriptions
   * @param connectors deferred subscription tasks (executed during assembly finalization)
   * @param loggedLive tells whether the node has written its output to the log live
   * @param checkpoint what the node writes to the checkpoint store
   * @return shared Flux for downstream consumption; connected by the registered connector
   */
  public Flux<Message<?>> applyLoggingAndBroadcasting(
      final String executionId,
      final String nodeId,
      final Flux<Message<?>> stream,
      final int bufferSize,
      final List<Disposable> disposables,
      final List<Runnable> connectors,
      final BooleanSupplier loggedLive,
      final NodePolicy.Checkpoint checkpoint) {
    log.atDebug()
        .setMessage("Applying logging and broadcasting - execution: {}, node: {}, buffer size: {}")
        .addArgument(executionId)
        .addArgument(nodeId)
        .addArgument(bufferSize)
        .log();

    final Flux<Message<?>> historiedStream = stream.map(msg -> msg.withAddedHistory(nodeId));
    final Flux<Message<?>> processedStream =
        getMessageFlux(executionId, nodeId, historiedStream, loggedLive, checkpoint);

    // publish() requests only what the slowest subscriber can take, so a slow child slows its
    // parent instead of overflowing a buffer. NodeBroadcast connects it once all edges subscribed.
    final NodeBroadcast broadcast =
        new NodeBroadcast(
            processedStream
                .doOnError(
                    err ->
                        log.atError()
                            .setMessage("Error in broadcast stream for node: {}")
                            .addArgument(nodeId)
                            .setCause(err)
                            .log())
                .publish(bufferSize),
            disposables);
    connectors.add(broadcast::connectIfUnobserved);
    return broadcast;
  }

  /**
   * Applies operators for conditional logging, checkpointing, and task tracking.
   *
   * <p>Pipeline:
   *
   * <ol>
   *   <li>Reactor logging (if DEBUG enabled on this class)
   *   <li>Checkpoint save to NodeCheckpointStore
   *   <li>Task tracker emission (if TRACE enabled)
   * </ol>
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param stream the incoming message stream
   * @return decorated Flux with logging, checkpoint, and tracking operators
   */
  private Flux<Message<?>> getMessageFlux(
      final String executionId,
      final String nodeId,
      final Flux<Message<?>> stream,
      final BooleanSupplier loggedLive,
      final NodePolicy.Checkpoint checkpoint) {
    log.atDebug()
        .setMessage("Building message flux for execution: {}, node: {}")
        .addArgument(executionId)
        .addArgument(nodeId)
        .log();

    Flux<Message<?>> logStream =
        stream.doOnNext(
            _ -> log.atDebug().setMessage("Node-{}: message received").addArgument(nodeId).log());

    log.atDebug()
        .setMessage("Applying checkpoint for execution: {}, node: {}")
        .addArgument(executionId)
        .addArgument(nodeId)
        .log();
    // A store that cannot save a message must not fail the run, the same as one that cannot write
    // the end marker. The node is then left unmarked, so a resume runs it again instead of
    // trusting a partial output.
    final AtomicBoolean saveFailed = new AtomicBoolean();
    final UnaryOperator<Mono<Void>> tolerant =
        save ->
            save.onErrorResume(
                error -> {
                  saveFailed.set(true);
                  log.atError()
                      .addKeyValue("executionId", executionId)
                      .addKeyValue("nodeId", nodeId)
                      .setCause(error)
                      .log("Could not save a checkpoint");
                  return Mono.empty();
                });
    switch (checkpoint) {
      case NONE -> saveFailed.set(true); // never marked complete: a resume runs the node again
      case LAST -> {
        final AtomicReference<Message<?>> last = new AtomicReference<>();
        logStream =
            logStream
                .doOnNext(last::set)
                .concatWith(
                    Mono.defer(
                            () ->
                                last.get() == null
                                    ? Mono.<Void>empty()
                                    : tolerant.apply(
                                        checkpointStore.save(executionId, nodeId, last.get())))
                        .then(Mono.empty()));
      }
      default ->
          // One write per batch instead of per message. A batch waits at most
          // CHECKPOINT_BATCH_WAIT, so a quiet node still passes its messages on.
          // ponytail: still on the stream thread; give it its own scheduler if stores stay slow.
          logStream =
              logStream
                  .bufferTimeout(
                      CHECKPOINT_BATCH_SIZE, Duration.ofMillis(CHECKPOINT_BATCH_WAIT_MILLIS), true)
                  .concatMap(
                      batch ->
                          tolerant
                              .apply(checkpointStore.saveAll(executionId, nodeId, batch))
                              .thenMany(Flux.fromIterable(batch)));
    }
    // Only a node that completed normally is marked, and its completion goes on downstream once the
    // marker is durable. A store that cannot write it must not fail the run.
    logStream =
        logStream.concatWith(
            Mono.defer(
                    () ->
                        saveFailed.get()
                            ? Mono.<Void>empty()
                            : checkpointStore.markComplete(executionId, nodeId))
                .onErrorResume(
                    error -> {
                      log.atError()
                          .addKeyValue("executionId", executionId)
                          .addKeyValue("nodeId", nodeId)
                          .setCause(error)
                          .log("Could not mark the node as complete");
                      return Mono.empty();
                    })
                .then(Mono.empty()));

    return logStream.doOnNext(
        msg -> {
          // nothing to echo (the plugin logged live) and nobody tracing: do not render the payload
          final boolean echo = !loggedLive.getAsBoolean();
          if (!echo && !log.isTraceEnabled()) {
            return;
          }
          // Bounds the payload echoed into the live log so large payloads are not copied per node.
          final String raw = String.valueOf(msg.payload());
          final String payload =
              raw.length() <= MAX_LOG_PAYLOAD_SIZE
                  ? raw
                  : raw.substring(0, MAX_LOG_PAYLOAD_SIZE)
                      + "... [truncated "
                      + (raw.length() - MAX_LOG_PAYLOAD_SIZE)
                      + " chars]";
          log.atTrace().setMessage("Processing message payload: {}").addArgument(payload).log();
          // A blank payload (the output of a quiet command, an empty body) has nothing to echo, and
          // the tracker rejects blank lines, which would otherwise fail the node.
          if (echo && !payload.isBlank()) {
            tracker.emitLogEvent(executionId, nodeId, payload);
          }
        });
  }
}
