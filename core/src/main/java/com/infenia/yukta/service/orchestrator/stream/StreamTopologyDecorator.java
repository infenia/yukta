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
import reactor.core.publisher.Sinks;

/**
 * Decorates reactive message streams with topology-aware routing, logging, and checkpointing.
 *
 * <p>Encapsulates complex Flux manipulation for:
 *
 * <ul>
 *   <li>Merging parent streams with edge-based routing and filtering
 *   <li>Applying logging, checkpoint operators, and task tracking
 *   <li>Broadcasting messages to downstream subscribers via multicast sinks
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

  /** Emit failure handler with retry logic. */
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      (_, emitResult) -> handleEmitFailure(emitResult);

  /** Maximum size for log event line in bytes. */
  private static final int MAX_LOG_LINE_SIZE = 16_384;

  // ~ Package-private visibility
  /* default */ static boolean handleEmitFailure(final Sinks.EmitResult emitResult) {
    final boolean shouldRetry = emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED;
    if (shouldRetry) {
      java.util.concurrent.locks.LockSupport.parkNanos(10_000);
    }
    return shouldRetry;
  }

  /**
   * Split a log line into chunks if it exceeds MAX_LOG_LINE_SIZE.
   *
   * <p>Preserves all log data by splitting at newline boundaries first, then splitting individual
   * lines that exceed MAX_LOG_LINE_SIZE character-by-character. Each chunk becomes a separate log
   * entry to avoid validation failures from oversized lines.
   *
   * @param line the log line to split
   * @return array of log chunks, each <= MAX_LOG_LINE_SIZE
   */
  private static String[] splitLogLine(final String line) {
    final String[] result;
    if (line.length() <= MAX_LOG_LINE_SIZE) {
      result = new String[] {line};
    } else {
      final List<String> chunks = new ArrayList<>();
      final String[] lines = line.split("\n", -1);
      final StringBuilder currentChunk = new StringBuilder();

      for (final String currentLine : lines) {
        processLine(currentLine, currentChunk, chunks);
      }

      flushChunk(currentChunk, chunks);
      result = chunks.toArray(new String[0]);
    }
    return result;
  }

  private static void processLine(
      final String currentLine, final StringBuilder currentChunk, final List<String> chunks) {
    final int potentialSize =
        currentChunk.length() + (currentChunk.isEmpty() ? 0 : 1) + currentLine.length();

    if (potentialSize <= MAX_LOG_LINE_SIZE) {
      if (!currentChunk.isEmpty()) {
        currentChunk.append('\n');
      }
      currentChunk.append(currentLine);
    } else {
      flushChunk(currentChunk, chunks);
      if (currentLine.length() > MAX_LOG_LINE_SIZE) {
        splitLongLine(currentLine, chunks);
      } else {
        currentChunk.append(currentLine);
      }
    }
  }

  private static void flushChunk(final StringBuilder currentChunk, final List<String> chunks) {
    if (!currentChunk.isEmpty()) {
      chunks.add(currentChunk.toString());
      currentChunk.setLength(0);
    }
  }

  /**
   * Split a single line that exceeds MAX_LOG_LINE_SIZE into character-bounded chunks.
   *
   * @param line the line to split
   * @param chunks the list to append chunks to
   */
  private static void splitLongLine(final String line, final List<String> chunks) {
    int offset = 0;
    while (offset < line.length()) {
      final int endOffset = Math.min(offset + MAX_LOG_LINE_SIZE, line.length());
      chunks.add(line.substring(offset, endOffset));
      offset = endOffset;
    }
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

    Flux<Message<?>> stream =
        streams[edge.parentIndex()].map(msg -> msg.withSourceNodeId(edge.sourceNodeId()));

    if (edge.sourcePort() != null) {
      log.atDebug().setMessage("Filtering by source port: {}").addArgument(edge.sourcePort()).log();
      stream = stream.filter(msg -> edge.sourcePort().equals(msg.getSourcePort()));
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
   * <p>Broadcasts via multicast sink to support multiple downstream subscribers without replaying
   * upstream operators.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param stream the incoming message stream
   * @param bufferSize multicast sink buffer size
   * @param disposables lifecycle management for subscriptions
   * @param connectors deferred subscription tasks (executed during assembly finalization)
   * @return multicast Flux for downstream consumption
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

    final Sinks.Many<Message<?>> sink = Sinks.many().multicast().onBackpressureBuffer(bufferSize);

    connectors.add(
        () -> {
          log.atDebug()
              .setMessage("Subscribing to processed stream for node: {}")
              .addArgument(nodeId)
              .log();
          disposables.add(
              processedStream.subscribe(
                  msg -> sink.emitNext(msg, RETRY_HANDLER),
                  err -> {
                    log.atError()
                        .setMessage("Error in broadcast stream for node: {}")
                        .addArgument(nodeId)
                        .setCause(err)
                        .log();
                    sink.emitError(err, RETRY_HANDLER);
                  },
                  () -> {
                    log.atDebug()
                        .setMessage("Broadcast stream completed for node: {}")
                        .addArgument(nodeId)
                        .log();
                    sink.emitComplete(RETRY_HANDLER);
                  }));
        });

    return sink.asFlux();
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
        logStream.flatMap(msg -> checkpointStore.save(executionId, nodeId, msg).thenReturn(msg));

    return logStream.doOnNext(
        msg -> {
          final String payload = String.valueOf(msg.getPayload());
          log.atTrace().setMessage("Processing message payload: {}").addArgument(payload).log();
          final String[] chunks = splitLogLine(payload);
          for (final String chunk : chunks) {
            tracker.emitLogEvent(executionId, nodeId, chunk);
          }
        });
  }
}
