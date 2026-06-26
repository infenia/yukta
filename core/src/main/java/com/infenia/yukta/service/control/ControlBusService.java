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
package com.infenia.yukta.service.control;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.directive.ControlSignalHandler;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/**
 * Service for managing the system's Control Bus.
 *
 * <p>Handles administrative commands, heartbeats, and performance metrics from plugins. Dispatches
 * signals to registered handlers for extensible processing.
 */
@Slf4j
@Service
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidCatchingGenericException",
  "PMD.LawOfDemeter",
  "PMD.OnlyOneReturn"
})
public class ControlBusService {

  /** Separator used to create composite keys from workflow and node IDs. */
  private static final String COMPOSITE_KEY_SEPARATOR = "\0";

  /** Number of messages to batch before processing. */
  private final int batchSize;

  /** Duration to wait before processing a batch. */
  private final Duration batchTimeout;

  /** Buffer size for the control sink. */
  private final int bufferSize;

  /** List of signal handlers for dispatching messages. */
  private final List<ControlSignalHandler> handlers;

  /** Cache for compiled workflow instances. */
  private final PreparedWorkflowCache preparedWorkflowCache;

  /** Workflow orchestrator for compiling workflows. */
  private final WorkflowOrchestrator orchestrator;

  /** Map of active plugins by composite key. */
  private final Map<String, Plugin> activePlugins = new ConcurrentHashMap<>();

  /** Sink for emitting control messages. */
  private Sinks.Many<Message<?>> controlSink =
      Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

  /** Handler for retry logic when emitting fails. */
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100));

  /**
   * Create a composite key from workflow ID and node ID.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the composite key
   */
  private static String compositeKey(final String workflowId, final String nodeId) {
    return workflowId + COMPOSITE_KEY_SEPARATOR + nodeId;
  }

  /**
   * Constructor for ControlBusService.
   *
   * @param batchSize the number of messages to batch before processing
   * @param batchTimeoutMs the timeout in milliseconds for batching
   * @param bufferSize the size of the control sink buffer (uses SMALL_BUFFER_SIZE if too small)
   * @param handlers the list of signal handlers for dispatching messages
   * @param preparedWorkflowCache the cache for compiled workflow instances
   * @param orchestrator the workflow orchestrator for compiling workflows
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public ControlBusService(
      @Value("${control.bus.batch.size:100}") final int batchSize,
      @Value("${control.bus.batch.timeout.ms:50}") final int batchTimeoutMs,
      @Value("${control.bus.buffer.size:256}") final int bufferSize,
      final List<ControlSignalHandler> handlers,
      final PreparedWorkflowCache preparedWorkflowCache,
      final WorkflowOrchestrator orchestrator) {
    this.batchSize = batchSize;
    this.batchTimeout = Duration.ofMillis(batchTimeoutMs);
    this.bufferSize = Math.max(bufferSize, Queues.SMALL_BUFFER_SIZE);
    this.handlers = List.copyOf(handlers);
    this.preparedWorkflowCache = preparedWorkflowCache;
    this.orchestrator = orchestrator;
  }

  /** Initialize the control sink and background event consumer. */
  @PostConstruct
  public void init() {
    if (bufferSize == Queues.SMALL_BUFFER_SIZE) {
      log.atDebug().log("Initialized control sink with default buffer size");
    } else {
      controlSink = Sinks.many().multicast().onBackpressureBuffer(bufferSize, false);
      log.atDebug()
          .addArgument(bufferSize)
          .log("Initialized control sink with custom buffer size: {}");
    }

    controlSink
        .asFlux()
        .publishOn(Schedulers.parallel())
        .bufferTimeout(batchSize, batchTimeout)
        .doOnSubscribe(
            _ -> log.atDebug().log("Control bus consumer subscribed, starting batch processing"))
        .concatMap(
            batch ->
                Mono.fromRunnable(() -> handleControlBatch(batch))
                    .doOnSuccess(
                        _ ->
                            log.atTrace()
                                .addArgument(batch.size())
                                .log("Processed control signal batch with {} messages"))
                    .onErrorResume(
                        e -> {
                          log.atError()
                              .addArgument(batch.size())
                              .setCause(e)
                              .log("Error processing control signal batch with {} messages");
                          return Mono.empty();
                        }))
        .doOnError(
            e -> log.atError().setCause(e).log("Control bus consumer encountered fatal error"))
        .subscribe();
  }

  /**
   * Emit a control signal to the bus.
   *
   * @param signal the control signal message
   * @return a Mono that completes when the signal is emitted
   */
  @SuppressWarnings("PMD.GuardLogStatement")
  public Mono<Void> emit(final Message<?> signal) {
    final Object payload = signal.getPayload();
    final String payloadType = payload != null ? payload.getClass().getSimpleName() : "null";
    log.atTrace().addArgument(payloadType).log("Emitting control signal of type: {}");
    return Mono.create(
        sink -> {
          try {
            controlSink.emitNext(signal, RETRY_HANDLER);
            log.atDebug()
                .addArgument(signal.getSourceNodeId())
                .addArgument(signal.getWorkflowId())
                .log("Control signal emitted from node: {} in workflow: {}");
            sink.success();
          } catch (final Exception e) {
            log.atError().setCause(e).log("Failed to emit control signal to bus");
            sink.error(new IllegalStateException("Control bus emit failed", e));
          }
        });
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
   * Compile and cache a workflow after it has already been persisted.
   *
   * <p>Assumes the workflow definition is already in {@link WorkflowDefinitionStore}. Invalidates
   * any stale cache entries, compiles the workflow, and warms the cache.
   *
   * @param sessionId the session identifier
   * @param workflowDefinition the workflow definition to compile and cache
   * @return a Mono that completes when the workflow is compiled and cached
   */
  public Mono<Void> compileAndCacheWorkflow(
      final String sessionId, final WorkflowDefinition workflowDefinition) {
    final String workflowId = workflowDefinition.workflowId();
    return Mono.fromRunnable(
            () -> {
              log.atDebug()
                  .addArgument(workflowId)
                  .addArgument(sessionId)
                  .log("Invalidating cache for workflow: {} in session: {}");
              preparedWorkflowCache.invalidate(sessionId, workflowId);
            })
        .then(
            orchestrator
                .prepareWorkflow(workflowDefinition)
                .doOnSubscribe(
                    _ ->
                        log.atDebug()
                            .addArgument(workflowId)
                            .addArgument(sessionId)
                            .log("Starting compilation of workflow: {} for session: {}"))
                .doOnSuccess(
                    prepared ->
                        log.atDebug()
                            .addArgument(workflowId)
                            .addArgument(sessionId)
                            .log("Successfully compiled workflow: {} for session: {}"))
                .doOnError(
                    err ->
                        log.atError()
                            .addArgument(workflowId)
                            .addArgument(sessionId)
                            .setCause(err)
                            .log("Failed to compile workflow: {} for session: {}")))
        .doOnNext(
            prepared -> {
              log.atDebug()
                  .addArgument(workflowId)
                  .addArgument(sessionId)
                  .log("Caching prepared workflow: {} for session: {}");
              preparedWorkflowCache.put(sessionId, workflowId, prepared);
            })
        .then();
  }

  /**
   * Get the last heartbeat for a node in a specific workflow.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null
   */
  @Nullable
  public Message<?> getLastHeartbeat(
      @NotBlank final String workflowId, @NotBlank final String nodeId) {
    final String key = compositeKey(workflowId, nodeId);
    return handlers.stream()
        .map(h -> h.getLastHeartbeat(key))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * Get the last statistics for a node in a specific workflow.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the last statistics message, or null
   */
  @Nullable
  public Message<?> getLastStatistics(
      @NotBlank final String workflowId, @NotBlank final String nodeId) {
    final String key = compositeKey(workflowId, nodeId);
    return handlers.stream()
        .map(h -> h.getLastStatistics(key))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * List all node IDs in a specific workflow that have emitted heartbeats.
   *
   * @param workflowId the workflow identifier
   * @return list of node IDs scoped to the workflow
   */
  public List<String> getActiveNodes(@NotBlank final String workflowId) {
    final String prefix = workflowId + COMPOSITE_KEY_SEPARATOR;
    return handlers.stream()
        .map(ControlSignalHandler::getActiveNodes)
        .filter(list -> !list.isEmpty())
        .findFirst()
        .orElse(List.of())
        .stream()
        .filter(key -> key.startsWith(prefix))
        .map(key -> key.substring(prefix.length()))
        .toList();
  }

  /**
   * List all node IDs across all workflows that have emitted heartbeats.
   *
   * @return list of all active node IDs
   */
  public List<String> getActiveNodes() {
    return handlers.stream()
        .map(ControlSignalHandler::getActiveNodes)
        .filter(list -> !list.isEmpty())
        .findFirst()
        .orElse(List.of());
  }

  /**
   * Register a plugin to receive control signals for a specific workflow node.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @param plugin the plugin instance
   */
  public void registerPlugin(
      @NotBlank final String workflowId, @NotBlank final String nodeId, final Plugin plugin) {
    final String key = compositeKey(workflowId, nodeId);
    activePlugins.put(key, plugin);
    log.atDebug()
        .addArgument(nodeId)
        .addArgument(workflowId)
        .addArgument(plugin.getClass().getSimpleName())
        .log("Registered plugin {} for node: {} in workflow: {}");
  }

  /**
   * Unregister a plugin from the control bus and clean up all state.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   */
  public void unregisterPlugin(@NotBlank final String workflowId, @NotBlank final String nodeId) {
    final String key = compositeKey(workflowId, nodeId);
    final Plugin removed = activePlugins.remove(key);
    if (removed != null) {
      log.atDebug()
          .addArgument(nodeId)
          .addArgument(workflowId)
          .log("Unregistered plugin for node: {} in workflow: {}");
    } else {
      log.atWarn()
          .addArgument(nodeId)
          .addArgument(workflowId)
          .log("Attempted to unregister plugin for non-existent node: {} in workflow: {}");
    }
    handlers.forEach(h -> h.removeNode(key));
  }

  /**
   * Send a command to a specific node in a workflow and wait for response.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the target node identifier
   * @param command the command message
   * @return a Mono of the response message
   */
  public Mono<Message<?>> sendCommand(
      @NotBlank final String workflowId, @NotBlank final String nodeId, final Message<?> command) {
    final String key = compositeKey(workflowId, nodeId);
    final Plugin plugin = activePlugins.get(key);
    if (plugin == null) {
      log.atWarn()
          .addArgument(nodeId)
          .addArgument(workflowId)
          .log("Command target node not found: {} in workflow: {}");
      return Mono.error(
          new IllegalArgumentException("Node not found: " + workflowId + "/" + nodeId));
    }
    final Object payload = command.getPayload();
    final String commandType = payload != null ? payload.getClass().getSimpleName() : "null";
    log.atDebug()
        .addArgument(commandType)
        .addArgument(nodeId)
        .addArgument(workflowId)
        .log("Sending control command of type {} to node: {} in workflow: {}");
    return plugin
        .onControlSignal(command)
        .doOnSuccess(
            response ->
                log.atDebug()
                    .addArgument(nodeId)
                    .addArgument(workflowId)
                    .log("Received response from node: {} in workflow: {}"))
        .doOnError(
            err ->
                log.atError()
                    .addArgument(nodeId)
                    .addArgument(workflowId)
                    .setCause(err)
                    .log("Error sending command to node: {} in workflow: {}"));
  }

  /**
   * Shutdown the control bus gracefully.
   *
   * <p>Signals completion on the control sink, terminating the control stream and allowing
   * subscribers to close cleanly.
   */
  public void shutdown() {
    controlSink.emitComplete(RETRY_HANDLER);
  }

  private void handleControlBatch(final List<Message<?>> batch) {
    log.atTrace().addArgument(batch.size()).log("Processing control signal batch of size: {}");
    final List<Message<?>> prioritized =
        batch.stream()
            .sorted(Comparator.comparingInt((final Message<?> m) -> m.getPriority()).reversed())
            .toList();

    int handledCount = 0;
    int skippedCount = 0;

    for (final Message<?> msg : prioritized) {
      if (processMessage(msg)) {
        handledCount++;
      } else {
        skippedCount++;
      }
    }
    log.atDebug()
        .addArgument(batch.size())
        .addArgument(handledCount)
        .addArgument(skippedCount)
        .log("Batch processing complete: {} messages, {} handled, {} skipped");
  }

  private boolean processMessage(final Message<?> msg) {
    final Object payload = msg.getPayload();
    final String nodeId = msg.getSourceNodeId();
    final String workflowId = msg.getWorkflowId();

    if (nodeId == null || payload == null || workflowId == null) {
      final String payloadStatus = payload != null ? "present" : "null";
      log.atWarn()
          .log(
              "Skipped signal due to missing required fields: nodeId={}, workflowId={},"
                  + " payload={}",
              nodeId,
              workflowId,
              payloadStatus);
      return false;
    }

    final String key = compositeKey(workflowId, nodeId);
    final String payloadType = payload.getClass().getSimpleName();

    for (final ControlSignalHandler handler : handlers) {
      if (handler.canHandle(payload)) {
        log.atTrace()
            .addArgument(payloadType)
            .addArgument(nodeId)
            .addArgument(workflowId)
            .addArgument(handler.getClass().getSimpleName())
            .log("Dispatching {} signal from node: {} in workflow: {} to handler: {}");
        handler.handle(key, msg, payload);
        return true;
      }
    }

    log.atWarn()
        .addArgument(payloadType)
        .addArgument(nodeId)
        .addArgument(workflowId)
        .log("No handler found for signal type {} from node: {} in workflow: {}");
    return false;
  }
}
