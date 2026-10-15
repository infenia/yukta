// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.directive.ControlSignalHandler;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.util.SinkEmit;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;
import reactor.util.retry.Retry;

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
  "PMD.OnlyOneReturn",
  "PMD.ExcessiveImports"
})
public class ControlBusService {

  /** Metadata key of a control message that names the run it comes from. */
  public static final String EXECUTION_ID_KEY = "executionId";

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

  /** Registry of plugin instances actively servicing workflow nodes. */
  private final ActivePluginRegistry activePluginRegistry;

  /** Applies execution control commands in order and reports when they were applied. */
  @SuppressWarnings("PMD.AvoidUsingVolatile")
  private volatile Function<ExecutionControlCommand, Mono<Void>> commandExecutor;

  /** Sink for emitting control messages. */
  private Sinks.Many<Message<?>> controlSink =
      Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

  /** First delay before the consumer resubscribes after a failure. */
  private static final Duration CONSUMER_RETRY_DELAY = Duration.ofMillis(100);

  /** Longest delay between resubscribe attempts. */
  private static final Duration CONSUMER_MAX_RETRY_DELAY = Duration.ofSeconds(5);

  /** Set once the bus is shutting down, so the consumer is no longer resubscribed. */
  private final AtomicBoolean stopped = new AtomicBoolean();

  /** Handler for retry logic when emitting fails. */
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100));

  /**
   * Constructor for ControlBusService.
   *
   * @param batchSize the number of messages to batch before processing
   * @param batchTimeoutMs the timeout in milliseconds for batching
   * @param bufferSize the size of the control sink buffer (uses SMALL_BUFFER_SIZE if too small)
   * @param handlers the list of signal handlers for dispatching messages
   * @param preparedWorkflowCache the cache for compiled workflow instances
   * @param orchestrator the workflow orchestrator for compiling workflows
   * @param activePluginRegistry the registry of plugin instances actively servicing nodes
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public ControlBusService(
      @Value("${control.bus.batch.size:100}") final int batchSize,
      @Value("${control.bus.batch.timeout.ms:50}") final int batchTimeoutMs,
      @Value("${control.bus.buffer.size:256}") final int bufferSize,
      final List<ControlSignalHandler> handlers,
      final PreparedWorkflowCache preparedWorkflowCache,
      final WorkflowOrchestrator orchestrator,
      final ActivePluginRegistry activePluginRegistry) {
    this.batchSize = batchSize;
    this.batchTimeout = Duration.ofMillis(batchTimeoutMs);
    this.bufferSize = Math.max(bufferSize, Queues.SMALL_BUFFER_SIZE);
    this.handlers = List.copyOf(handlers);
    this.preparedWorkflowCache = preparedWorkflowCache;
    this.orchestrator = orchestrator;
    this.activePluginRegistry = activePluginRegistry;
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
        .retryWhen(
            Retry.backoff(Long.MAX_VALUE, CONSUMER_RETRY_DELAY)
                .maxBackoff(CONSUMER_MAX_RETRY_DELAY)
                .filter(_ -> !stopped.get())
                .doBeforeRetry(
                    signal ->
                        log.atError()
                            .setCause(signal.failure())
                            .log("Control bus consumer failed, resubscribing")))
        .then()
        .subscribe(
            null, e -> log.atError().setCause(e).log("Control bus consumer stopped unexpectedly"));
  }

  /**
   * Registers the component that applies execution control commands.
   *
   * <p>Once set, {@link #emit} completes for a command only after it has been applied (or fails
   * with the reason it was rejected), and commands for one execution are applied in order.
   *
   * @param executor applies one command
   */
  public void setCommandExecutor(final Function<ExecutionControlCommand, Mono<Void>> executor) {
    this.commandExecutor = executor;
  }

  /**
   * Emit a control signal to the bus.
   *
   * <p>An execution control command is applied in order and this completes only once it has been
   * applied (or fails with the reason it was rejected).
   *
   * @param signal the control signal message
   * @return a Mono that completes when the signal is emitted
   */
  @SuppressWarnings("PMD.GuardLogStatement")
  public Mono<Void> emit(final Message<?> signal) {
    final Object payload = signal.payload();
    final Function<ExecutionControlCommand, Mono<Void>> executor = commandExecutor;
    if (executor != null && payload instanceof ExecutionControlCommand command) {
      return executor.apply(command);
    }
    final String payloadType = payload != null ? payload.getClass().getSimpleName() : "null";
    log.atTrace().addArgument(payloadType).log("Emitting control signal of type: {}");
    return Mono.create(
        sink -> {
          try {
            if (!SinkEmit.offer(controlSink, signal)) {
              sink.error(new IllegalStateException("Control bus is full or closed"));
              return;
            }
            log.atDebug()
                .addArgument(signal.sourceNodeId())
                .addArgument(signal.workflowId())
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
   * Get the last heartbeat for a node in a specific workflow: the newest one from any run of it.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null
   */
  @Nullable
  public Message<?> getLastHeartbeat(
      @NotBlank final String workflowId, @NotBlank final String nodeId) {
    return newest(workflowId, nodeId, ControlSignalHandler::getLastHeartbeat);
  }

  /**
   * Get the last heartbeat for a node of one run.
   *
   * @param workflowId the workflow identifier
   * @param executionId the run
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null
   */
  @Nullable
  public Message<?> getLastHeartbeat(
      @NotBlank final String workflowId,
      @Nullable final String executionId,
      @NotBlank final String nodeId) {
    return read(
        ControlKey.forNode(workflowId, executionId, nodeId),
        ControlSignalHandler::getLastHeartbeat);
  }

  /**
   * Get the last statistics for a node in a specific workflow: the newest from any run of it.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the last statistics message, or null
   */
  @Nullable
  public Message<?> getLastStatistics(
      @NotBlank final String workflowId, @NotBlank final String nodeId) {
    return newest(workflowId, nodeId, ControlSignalHandler::getLastStatistics);
  }

  /**
   * Get the last statistics for a node of one run.
   *
   * @param workflowId the workflow identifier
   * @param executionId the run
   * @param nodeId the node identifier
   * @return the last statistics message, or null
   */
  @Nullable
  public Message<?> getLastStatistics(
      @NotBlank final String workflowId,
      @Nullable final String executionId,
      @NotBlank final String nodeId) {
    return read(
        ControlKey.forNode(workflowId, executionId, nodeId),
        ControlSignalHandler::getLastStatistics);
  }

  /** The message a handler holds for a key. */
  @Nullable
  private Message<?> read(
      final String key, final BiFunction<ControlSignalHandler, String, Message<?>> getter) {
    return handlers.stream()
        .map(h -> getter.apply(h, key))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** The newest message of a node over every run that reported, and over reports without a run. */
  @Nullable
  private Message<?> newest(
      final String workflowId,
      final String nodeId,
      final BiFunction<ControlSignalHandler, String, Message<?>> getter) {
    return Stream.concat(
            Stream.of(ControlKey.forNode(workflowId, null, nodeId)),
            activeKeys().stream().filter(key -> ControlKey.isFor(key, workflowId, nodeId)))
        .distinct()
        .map(key -> read(key, getter))
        .filter(Objects::nonNull)
        .max(
            Comparator.comparing(
                Message::timestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
        .orElse(null);
  }

  /** The keys every handler holds state for. */
  private List<String> activeKeys() {
    return handlers.stream()
        .map(ControlSignalHandler::getActiveNodes)
        .flatMap(List::stream)
        .toList();
  }

  /**
   * List all node IDs in a specific workflow that have emitted heartbeats.
   *
   * @param workflowId the workflow identifier
   * @return list of node IDs scoped to the workflow, each once however many runs reported
   */
  public List<String> getActiveNodes(@NotBlank final String workflowId) {
    return activeKeys().stream()
        .filter(key -> ControlKey.isIn(key, workflowId))
        .map(ControlKey::nodeId)
        .distinct()
        .toList();
  }

  /**
   * List all node IDs across all workflows that have emitted heartbeats.
   *
   * @return list of all active node IDs, each once
   */
  public List<String> getActiveNodes() {
    return activeKeys().stream().map(ControlKey::nodeId).distinct().toList();
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
    activePluginRegistry.register(workflowId, nodeId, plugin);
  }

  /**
   * Unregister a plugin from the control bus and clean up all state.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   */
  public void unregisterPlugin(@NotBlank final String workflowId, @NotBlank final String nodeId) {
    activePluginRegistry.unregister(workflowId, nodeId);
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
    final Plugin plugin = activePluginRegistry.lookup(workflowId, nodeId).orElse(null);
    if (plugin == null) {
      log.atWarn()
          .addArgument(nodeId)
          .addArgument(workflowId)
          .log("Command target node not found: {} in workflow: {}");
      return Mono.error(new NotFoundException("Node not found: " + workflowId + "/" + nodeId));
    }
    final Object payload = command.payload();
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
    stopped.set(true);
    controlSink.emitComplete(RETRY_HANDLER);
  }

  private void handleControlBatch(final List<Message<?>> batch) {
    log.atTrace().addArgument(batch.size()).log("Processing control signal batch of size: {}");
    final List<Message<?>> prioritized =
        batch.stream()
            .sorted(Comparator.comparingInt((final Message<?> m) -> m.priority()).reversed())
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

  /** The run a control message comes from, or null if it does not say. */
  @Nullable
  private static String executionIdOf(final Message<?> msg) {
    final Object executionId = msg.metadata() == null ? null : msg.metadata().get(EXECUTION_ID_KEY);
    return executionId == null ? null : executionId.toString();
  }

  private boolean processMessage(final Message<?> msg) {
    final Object payload = msg.payload();
    final String nodeId = msg.sourceNodeId();
    final String workflowId = msg.workflowId();

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

    final String key = ControlKey.forNode(workflowId, executionIdOf(msg), nodeId);
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
