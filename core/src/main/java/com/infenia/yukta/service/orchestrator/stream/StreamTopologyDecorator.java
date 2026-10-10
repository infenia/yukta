// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

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

  /** Constant for single parent node. */
  private static final int SINGLE_PARENT = 1;

  /** The task tracker service for tracking task execution. */
  private final TaskTrackerService tracker;

  /** The node checkpoint store for persisting node state. */
  private final NodeCheckpointStore checkpointStore;

  /** Maximum number of payload characters echoed to the live log per message. */
  private static final int MAX_LOG_PAYLOAD_SIZE = 2_048;

  /**
   * Bounds the payload echoed into the live log so large payloads are not copied once per node.
   *
   * @param payload the stringified payload
   * @return the payload, cut to {@code MAX_LOG_PAYLOAD_SIZE} characters with a marker if longer
   */
  private static String truncateForLog(final String payload) {
    return payload.length() <= MAX_LOG_PAYLOAD_SIZE
        ? payload
        : payload.substring(0, MAX_LOG_PAYLOAD_SIZE)
            + "... [truncated "
            + (payload.length() - MAX_LOG_PAYLOAD_SIZE)
            + " chars]";
  }

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
  @SuppressWarnings("PMD.OnlyOneReturn")
  public Flux<Message<?>> mergeParentStreams(
      final Flux<Message<?>>[] streams, final ParentEdgeInfo... parentEdges) {
    log.atDebug().setMessage("Merging {} parent streams").addArgument(parentEdges.length).log();

    final List<Flux<Message<?>>> parentFluxes = new ArrayList<>(parentEdges.length);
    for (final ParentEdgeInfo edge : parentEdges) {
      parentFluxes.add(applyEdgeRouting(streams, edge));
    }

    if (parentFluxes.isEmpty()) {
      log.atDebug().setMessage("No parent streams to merge, returning empty flux").log();
      return Flux.empty();
    } else if (parentFluxes.size() == SINGLE_PARENT) {
      log.atDebug().setMessage("Single parent stream, returning without merge").log();
      return parentFluxes.getFirst();
    }

    log.atDebug()
        .setMessage("Multiple parent streams merged: {} streams")
        .addArgument(parentFluxes.size())
        .log();
    return Flux.merge(parentFluxes);
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
    log.atDebug()
        .setMessage("Applying edge routing - parent index: {}, source node: {}, source port: {}")
        .addArgument(edge.parentIndex())
        .addArgument(edge.sourceNodeId())
        .addArgument(edge.sourcePort())
        .log();

    if (streams[edge.parentIndex()] instanceof NodeBroadcast broadcast) {
      broadcast.expectSubscriber();
    }
    Flux<Message<?>> stream =
        streams[edge.parentIndex()].map(msg -> msg.withSourceNodeId(edge.sourceNodeId()));

    final String sourcePort = edge.sourcePort();
    if (sourcePort != null) {
      log.atDebug().setMessage("Filtering by source port: {}").addArgument(sourcePort).log();
      stream = stream.filter(msg -> sourcePort.equals(msg.sourcePort()));
    }
    if (edge.tolerateFailure()) {
      stream =
          stream.onErrorResume(
              error -> {
                log.atWarn()
                    .addKeyValue("sourceNodeId", edge.sourceNodeId())
                    .setCause(error)
                    .log("Parent failed; continuing without it (trigger=ALL_DONE)");
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
    log.atDebug()
        .setMessage("Applying logging and broadcasting - execution: {}, node: {}, buffer size: {}")
        .addArgument(executionId)
        .addArgument(nodeId)
        .addArgument(bufferSize)
        .log();

    final Flux<Message<?>> historiedStream = stream.map(msg -> msg.withAddedHistory(nodeId));
    final Flux<Message<?>> processedStream = getMessageFlux(executionId, nodeId, historiedStream);

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
      final String executionId, final String nodeId, final Flux<Message<?>> stream) {
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
    logStream =
        logStream.concatMap(msg -> checkpointStore.save(executionId, nodeId, msg).thenReturn(msg));

    return logStream.doOnNext(
        msg -> {
          final String payload = truncateForLog(String.valueOf(msg.payload()));
          log.atTrace().setMessage("Processing message payload: {}").addArgument(payload).log();
          // A blank payload (the output of a quiet command, an empty body) has nothing to echo, and
          // the tracker rejects blank lines, which would otherwise fail the node.
          if (!payload.isBlank()) {
            tracker.emitLogEvent(executionId, nodeId, payload);
          }
        });
  }
}
