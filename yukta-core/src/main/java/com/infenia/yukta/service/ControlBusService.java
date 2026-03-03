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

import com.infenia.yukta.plugin.message.Message;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/**
 * Service for managing the system's Control Bus.
 *
 * <p>Handles administrative commands, heartbeats, and performance metrics from plugins.
 */
@Slf4j
@Service
public class ControlBusService {

  private static final int BATCH_SIZE = 100;
  private static final Duration BATCH_TIMEOUT = Duration.ofMillis(50);
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100));
  private final Sinks.Many<Message<?>> controlSink =
      Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
  private final Map<String, Message<?>> lastHeartbeats = new ConcurrentHashMap<>();
  private final Map<String, Message<?>> lastStatistics = new ConcurrentHashMap<>();
  private final Map<String, com.infenia.yukta.plugin.core.WorkflowPlugin> activePlugins =
      new ConcurrentHashMap<>();

  /** Default constructor. */
  public ControlBusService() {
    // Standard service initialization
  }

  /** Initialize background event consumers. */
  @PostConstruct
  public void init() {
    controlSink
        .asFlux()
        .publishOn(Schedulers.parallel())
        .bufferTimeout(BATCH_SIZE, BATCH_TIMEOUT)
        .concatMap(
            batch ->
                Mono.fromRunnable(() -> handleControlBatch(batch))
                    .onErrorResume(
                        e -> {
                          log.error("Error processing control signal batch", e);
                          return Mono.empty();
                        }))
        .subscribe();
  }

  /**
   * Emit a control signal to the bus.
   *
   * @param signal the control signal message
   * @return a Mono that completes when the signal is emitted
   */
  public Mono<Void> emit(final Message<?> signal) {
    return Mono.fromRunnable(() -> controlSink.emitNext(signal, RETRY_HANDLER));
  }

  /**
   * Get a stream of all control signals.
   *
   * @return a Flux of control messages
   */
  public Flux<Message<?>> getControlStream() {
    return controlSink.asFlux();
  }

  /**
   * Get the last heartbeat for a node.
   *
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null
   */
  public Message<?> getLastHeartbeat(final String nodeId) {
    return lastHeartbeats.get(nodeId);
  }

  /**
   * List all node IDs that have emitted heartbeats.
   *
   * @return list of node IDs
   */
  public List<String> getActiveNodes() {
    return List.copyOf(lastHeartbeats.keySet());
  }

  /**
   * Register a plugin to receive control signals.
   *
   * @param nodeId the node identifier
   * @param plugin the plugin instance
   */
  public void registerPlugin(
      final String nodeId, final com.infenia.yukta.plugin.core.WorkflowPlugin plugin) {
    activePlugins.put(nodeId, plugin);
  }

  /**
   * Unregister a plugin from the control bus.
   *
   * @param nodeId the node identifier
   */
  public void unregisterPlugin(final String nodeId) {
    activePlugins.remove(nodeId);
  }

  /**
   * Send a command to a specific node and wait for response.
   *
   * @param nodeId the target node identifier
   * @param command the command message
   * @return a Mono of the response message
   */
  public Mono<Message<?>> sendCommand(final String nodeId, final Message<?> command) {
    final com.infenia.yukta.plugin.core.WorkflowPlugin plugin = activePlugins.get(nodeId);
    final Mono<Message<?>> result;
    if (plugin == null) {
      result = Mono.error(new IllegalArgumentException("Node not found: " + nodeId));
    } else {
      result = plugin.onControlSignal(command);
    }
    return result;
  }

  @SuppressWarnings("PMD.LawOfDemeter")
  private void handleControlBatch(final List<Message<?>> batch) {
    final List<Message<?>> prioritized =
        batch.stream()
            .sorted(Comparator.comparingInt((Message<?> m) -> m.getPriority()).reversed())
            .toList();
    for (final Message<?> msg : prioritized) {
      final Object payload = msg.getPayload();
      final String nodeId = msg.getSourceNodeId();
      if (nodeId != null) {
        if (payload instanceof com.infenia.yukta.plugin.message.control.ControlHeartbeat) {
          lastHeartbeats.put(nodeId, msg);
        } else if (payload instanceof com.infenia.yukta.plugin.message.control.ControlStatistics) {
          lastStatistics.put(nodeId, msg);
        }
      }
    }
  }
}
