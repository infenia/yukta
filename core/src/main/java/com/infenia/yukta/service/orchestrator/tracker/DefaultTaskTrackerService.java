// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.event.TrackerEvent;
import com.infenia.yukta.event.WorkflowLogEvent;
import com.infenia.yukta.event.WorkflowStatusEvent;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.streaming.StatusHistoryCache;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import com.infenia.yukta.util.SinkEmit;
import com.infenia.yukta.validation.NodeId;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.Disposable;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Service for tracking the progress of workflows and tasks. */
@Slf4j
@Service
@Validated
@SuppressWarnings({
  "PMD.GodClass",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidDuplicateLiterals"
})
public class DefaultTaskTrackerService implements TaskTrackerService {

  /** Batch size for event processing. */
  private static final int BATCH_SIZE = 100;

  /** How far up a tree of runs {@link #isWithin} looks (sub-workflows nest at most 5 deep). */
  private static final int MAX_TREE_DEPTH = 16;

  /** Batch timeout duration for event processing. */
  private static final Duration BATCH_TIMEOUT = Duration.ofMillis(50);

  /** Finished executions kept per session for history; older ones are dropped on cleanup. */
  /* default */ static final int MAX_FINISHED_PER_SESSION =
      ExecutionHistory.MAX_FINISHED_PER_SESSION;

  /** Most log events that may wait to be applied before further ones are dropped. */
  private static final int MAX_PENDING_LOGS = 10_000;

  /** Metric counting events the tracker could not queue. */
  private static final String DROPPED_METRIC = "yukta.tracker.dropped";

  /** Time to live for execution data after terminal status. */
  private final Duration cleanupTtl;

  /** Metrics and observations of runs and nodes. */
  private final RunTelemetry telemetry;

  /** Map of session ID to workflow states. */
  private final Map<String, Map<String, WorkflowState>> sessionStates = new ConcurrentHashMap<>();

  /** When each session last started or ended a run, to pick the one to evict. */
  private final Map<String, Long> sessionUsed = new ConcurrentHashMap<>();

  /** Clock for {@link #sessionUsed}, in nanoseconds; replaced in tests. */
  private LongSupplier clock = System::nanoTime;

  /**
   * How long a session with no run going is kept after its last run started or ended; unset keeps
   * every session ({@code yukta.tracker.session-ttl}, for example {@code 24h}).
   */
  @Value("${yukta.tracker.session-ttl:#{null}}")
  private @Nullable Duration sessionTtl;

  /** Periodically drops the sessions that have been idle past {@link #sessionTtl}. */
  private @Nullable Disposable sessionSweeper;

  /** Most sessions kept in memory; 0 keeps them all. Evicted history is served from the log. */
  @Value("${yukta.tracker.max-sessions:0}")
  private int maxSessions;

  /** Map of execution ID to workflow state. */
  private final Map<String, WorkflowState> executionIndex = new ConcurrentHashMap<>();

  /** Map of workflow ID to latest execution ID. */
  private final Map<String, String> latestExecs = new ConcurrentHashMap<>();

  /** Map of workflow ID to status sinks. */
  private final Map<String, Sinks.Many<WorkflowProgress>> statusSinks = new ConcurrentHashMap<>();

  /**
   * Sink for every tracker event. A single ordered stream guarantees that a node's last status is
   * applied before the workflow's terminal status, and that log lines keep their place.
   */
  private final Sinks.Many<TrackerEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

  /** Log events accepted but not yet consumed; bounds how much a chatty plugin can queue. */
  private final AtomicInteger pendingLogs = new AtomicInteger();

  /** Live log and status streams of the executions. */
  private final ExecutionStreams streams;

  /** Finished executions remembered for each session. */
  private final ExecutionHistory history;

  /** Applies queued events to the execution states. */
  private final TrackerEventApplier applier;

  /**
   * Create a new DefaultTaskTrackerService with configurable cleanup TTL.
   *
   * @param cleanupTtl the time to live for execution data after terminal status
   * @param statusHistoryCache the cache for storing recent workflow progress updates
   * @param executionLog durable log of run starts and ends
   * @param meters registry for the node metrics
   * @param observations registry for the node observations; each node run is one observation named
   *     {@code yukta.node}
   * @param eventFeed receives what happens to every execution
   */
  @Autowired
  public DefaultTaskTrackerService(
      @Value("${yukta.tracker.cleanup-ttl:10m}") final Duration cleanupTtl,
      final StatusHistoryCache statusHistoryCache,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final ObservationRegistry observations,
      final ExecutionEventFeed eventFeed) {
    this.cleanupTtl = cleanupTtl;
    this.telemetry = new RunTelemetry(meters, observations);
    Gauge.builder(
            "yukta.tracker.queue.depth",
            eventSink,
            sink -> Scannable.from(sink).scanOrDefault(Scannable.Attr.BUFFERED, 0))
        .description("Tracker events accepted but not yet applied")
        .register(meters);
    this.streams =
        new ExecutionStreams(
            statusSinks,
            statusHistoryCache,
            this::findState,
            state -> getProgress(state.sessionId, state.executionId));
    this.history = new ExecutionHistory(sessionStates, executionIndex);
    this.applier =
        new TrackerEventApplier(
            this::findState,
            telemetry,
            executionLog,
            eventFeed,
            streams,
            pendingLogs,
            this::scheduleCleanup);
  }

  /**
   * Create a tracker whose events nobody listens to.
   *
   * @param cleanupTtl the time to live for execution data after terminal status
   * @param statusHistoryCache the cache for storing recent workflow progress updates
   * @param executionLog durable log of run starts and ends
   * @param meters registry for the node metrics
   * @param observations registry for the node observations
   */
  public DefaultTaskTrackerService(
      final Duration cleanupTtl,
      final StatusHistoryCache statusHistoryCache,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final ObservationRegistry observations) {
    this(
        cleanupTtl,
        statusHistoryCache,
        executionLog,
        meters,
        observations,
        new ExecutionEventFeed());
  }

  /**
   * Create a tracker without node observations.
   *
   * @param cleanupTtl the time to live for execution data after terminal status
   * @param statusHistoryCache the cache for storing recent workflow progress updates
   * @param executionLog durable log of run starts and ends
   * @param meters registry for the node metrics
   */
  public DefaultTaskTrackerService(
      final Duration cleanupTtl,
      final StatusHistoryCache statusHistoryCache,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters) {
    this(cleanupTtl, statusHistoryCache, executionLog, meters, ObservationRegistry.NOOP);
  }

  /**
   * Create a tracker that keeps its execution log in memory.
   *
   * @param cleanupTtl the time to live for execution data after terminal status
   * @param statusHistoryCache the cache for storing recent workflow progress updates
   */
  public DefaultTaskTrackerService(
      final Duration cleanupTtl, final StatusHistoryCache statusHistoryCache) {
    this(
        cleanupTtl, statusHistoryCache, new InMemoryExecutionLogStore(), new SimpleMeterRegistry());
  }

  /** Initialize background event consumers. */
  @PostConstruct
  public void init() {
    log.atInfo()
        .addKeyValue("cleanupTtl", cleanupTtl)
        .addKeyValue("batchSize", BATCH_SIZE)
        .addKeyValue("batchTimeoutMs", BATCH_TIMEOUT.toMillis())
        .log("Initialized DefaultTaskTrackerService with event consumers");

    eventSink
        .asFlux()
        .publishOn(Schedulers.parallel())
        .bufferTimeout(BATCH_SIZE, BATCH_TIMEOUT)
        .concatMap(
            batch ->
                Mono.fromRunnable(() -> handleEvents(batch))
                    .onErrorResume(
                        e -> {
                          log.atError()
                              .addKeyValue("batchSize", batch.size())
                              .log("Error processing tracker event batch", e);
                          return Mono.empty();
                        }))
        .then()
        .subscribe(
            null,
            e -> log.atError().setCause(e).log("Tracker event consumer stopped unexpectedly"));
    startSessionSweeper();
  }

  private void startSessionSweeper() {
    final Duration ttl = sessionTtl;
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      return;
    }
    // often enough that a session leaves soon after its time is up, rarely enough to cost nothing
    final Duration period =
        Duration.ofMillis(Math.clamp(ttl.dividedBy(4).toMillis(), 1_000L, 60_000L));
    sessionSweeper =
        Flux.interval(period, Schedulers.parallel())
            .concatMap(
                _ ->
                    Mono.fromRunnable(this::evictExpiredSessions)
                        .onErrorResume(
                            e -> {
                              log.atError()
                                  .setCause(e)
                                  .log("Dropping idle tracker sessions failed; will try again");
                              return Mono.empty();
                            }))
            .subscribe();
    log.atInfo()
        .addKeyValue("sessionTtl", ttl)
        .addKeyValue("sweepEvery", period)
        .log("Dropping tracker sessions that have been idle past the session TTL");
  }

  /** Stops the sweep of idle sessions. */
  @PreDestroy
  public void stopSessionSweeper() {
    Optional.ofNullable(sessionSweeper).ifPresent(Disposable::dispose);
  }

  /**
   * Start tracking a new workflow execution for a session.
   *
   * @param executionId the unique execution identifier
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param nodeIds the list of node IDs in the workflow DAG
   * @return a Mono that completes when the workflow tracking is started
   */
  @Override
  public Mono<Void> startWorkflow(
      @NotBlank final String executionId,
      @SessionId final String sessionId,
      @WorkflowId final String workflowId,
      @NotEmpty final List<String> nodeIds) {
    return Mono.fromRunnable(
        () -> {
          final WorkflowState state =
              new WorkflowState(
                  executionId,
                  sessionId,
                  workflowId,
                  "RUNNING",
                  nodeIds,
                  LocalDateTime.now(ZoneId.systemDefault()));

          sessionStates
              .computeIfAbsent(sessionId, _ -> new ConcurrentHashMap<>())
              .put(executionId, state);

          executionIndex.put(executionId, state);
          sessionUsed.put(sessionId, clock.getAsLong());
          evictOldSessions(sessionId);
          telemetry.runStarted(executionId, sessionId, workflowId);

          latestExecs.put(getLatestKey(sessionId, workflowId), executionId);

          streams.open(executionId);

          log.atInfo()
              .addKeyValue("executionId", executionId)
              .addKeyValue("sessionId", sessionId)
              .addKeyValue("workflowId", workflowId)
              .addKeyValue("nodeCount", nodeIds.size())
              .log("Started tracking workflow execution");
        });
  }

  /**
   * Update the status of a specific node.
   *
   * @param executionId the execution identifier
   * @param nodeId the ID of the node
   * @param module the module name
   * @param status the new status
   * @return a Mono that completes when the task status is updated
   */
  @Override
  public Mono<Void> updateTaskStatus(
      @NotBlank final String executionId,
      @NodeId final String nodeId,
      @NotBlank @Size(max = 256) final String module,
      @NotNull final TaskStatus status) {
    return updateTaskStatus(executionId, nodeId, module, status, Map.of());
  }

  /**
   * Update the status and metadata of a specific node.
   *
   * @param executionId the execution identifier
   * @param nodeId the ID of the node
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   * @return a Mono that completes when the task status is updated
   */
  @Override
  public Mono<Void> updateTaskStatus(
      @NotBlank final String executionId,
      @NodeId final String nodeId,
      @NotBlank @Size(max = 256) final String module,
      @NotNull final TaskStatus status,
      @NotNull final Map<String, Object> metadata) {
    return Mono.fromRunnable(
        () -> emitTaskStatusEvent(executionId, nodeId, module, status, metadata));
  }

  /**
   * Emit a task status event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   */
  @Override
  public void emitTaskStatusEvent(
      @NotBlank final String executionId,
      @NotBlank final String nodeId,
      @NotBlank @Size(max = 256) final String module,
      @NotNull final TaskStatus status,
      @NotNull final Map<String, Object> metadata) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("nodeId", nodeId)
        .addKeyValue("module", module)
        .addKeyValue("status", status)
        .addKeyValue("metadataSize", metadata.size())
        .log("Emitting task status event");
    publish(TaskStatusEvent.create(executionId, nodeId, module, status.name(), metadata));
  }

  @Override
  public void recordDefinitionVersion(
      @NotBlank final String executionId, @NotBlank final String version) {
    final WorkflowState state = findState(executionId);
    if (state != null) {
      state.definitionVersion = version;
    }
  }

  @Override
  public void recordLineage(
      @NotBlank final String executionId,
      @Nullable final String parentExecutionId,
      @Nullable final String restartOf) {
    final WorkflowState state = findState(executionId);
    if (state != null) {
      final WorkflowState parent = parentExecutionId == null ? null : findState(parentExecutionId);
      state.parentExecutionId = parentExecutionId;
      state.restartOf = restartOf;
      if (parentExecutionId == null) {
        state.rootExecutionId = executionId;
      } else if (parent != null && parent.rootExecutionId != null) {
        state.rootExecutionId = parent.rootExecutionId;
      } else {
        state.rootExecutionId = parentExecutionId;
      }
    }
  }

  @Override
  public boolean isWithin(@NotBlank final String executionId, @NotBlank final String ancestorId) {
    String current = executionId;
    // a run nests at most a few levels deep; the bound also ends a broken (cyclic) record
    for (int level = 0; current != null && level <= MAX_TREE_DEPTH; level++) {
      if (current.equals(ancestorId)) {
        return true;
      }
      current =
          Optional.ofNullable(findState(current))
              .map(state -> state.parentExecutionId)
              .orElse(null);
    }
    return false;
  }

  @Override
  public Lineage lineage(@NotBlank final String executionId) {
    // bounded like isWithin, so a broken (cyclic) record ends
    final List<String> chain =
        Stream.iterate(executionId, Objects::nonNull, this::parentOf)
            .limit(MAX_TREE_DEPTH + 1L)
            .toList();
    return new Lineage(chain.getLast(), chain.size() - 1);
  }

  private @Nullable String parentOf(final String executionId) {
    return Optional.ofNullable(findState(executionId))
        .map(state -> state.parentExecutionId)
        .orElse(null);
  }

  @Override
  public List<WorkflowExecutionSummary> getTree(@NotBlank final String rootExecutionId) {
    return executionIndex.values().stream()
        .filter(state -> isWithin(state.executionId, rootExecutionId))
        .map(ExecutionHistory::summary)
        .sorted(Comparator.comparing(WorkflowExecutionSummary::startTime))
        .toList();
  }

  /**
   * Record an operator control state for an execution and notify status subscribers.
   *
   * @param executionId the execution identifier
   * @param state the new control state
   */
  @Override
  public void emitControlStateEvent(
      @NotBlank final String executionId, @NotNull final ControlState state) {
    final WorkflowState workflowState = findState(executionId);
    if (workflowState == null) {
      log.atWarn()
          .addKeyValue("executionId", executionId)
          .addKeyValue("controlState", state)
          .log("Received control state for unknown execution");
      return;
    }
    workflowState.setControlState(state.name());
    streams.notifyStatusChange(workflowState);
  }

  @Override
  public void emitBreakpointEvent(
      @NotBlank final String executionId,
      @NotBlank final String nodeId,
      final TaskProgress.@Nullable Breakpoint breakpoint) {
    Optional.ofNullable(findState(executionId))
        .ifPresent(
            workflowState -> {
              workflowState.setBreakpoint(nodeId, breakpoint);
              streams.notifyStatusChange(workflowState);
            });
  }

  @Override
  public void recordRetry(final String module) {
    telemetry.retried(module);
  }

  @Override
  public void recordEdgeDropped(final String module) {
    telemetry.dropped("yukta.edge.dropped", module);
  }

  /**
   * Emit a workflow status event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param status the final status
   */
  @Override
  public void emitWorkflowStatusEvent(
      @NotBlank final String executionId, @NotNull final TaskStatus status) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("status", status)
        .log("Emitting workflow status event");
    publish(WorkflowStatusEvent.create(executionId, status.name()));
  }

  /**
   * Emit a log event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   */
  @Override
  public void emitLogEvent(
      @NotBlank final String executionId,
      @NotBlank final String nodeId,
      @NotBlank @Size(max = 16_384) final String line) {
    emitLogEvent(executionId, nodeId, line, false);
  }

  /**
   * Emit a log event, remembering which stream the line came from.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   * @param stderr true if the line came from the plugin's error stream
   */
  @Override
  public void emitLogEvent(
      @NotBlank final String executionId,
      @NotBlank final String nodeId,
      @NotBlank @Size(max = 16_384) final String line,
      final boolean stderr) {
    emitLogEvent(executionId, nodeId, line, stderr, null);
  }

  /**
   * Emit a log event with the level its plugin gave it. Every producer routes through here, so a
   * text of several lines is split into one event per line and each line is rated on its own.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   * @param stderr true if the line came from the plugin's error stream
   * @param level the level, or {@code null} to have the tracker rate the line
   */
  @Override
  public void emitLogEvent(
      @NotBlank final String executionId,
      @NotBlank final String nodeId,
      @NotBlank @Size(max = 16_384) final String line,
      final boolean stderr,
      @Nullable final LogLevel level) {
    log.atTrace()
        .addKeyValue("executionId", executionId)
        .addKeyValue("nodeId", nodeId)
        .addKeyValue("lineLength", line.length())
        .log("Emitting workflow log event");
    for (final String piece : line.split("\\R")) {
      if (!piece.isBlank()) {
        emitSingleLine(executionId, nodeId, piece, stderr, level);
      }
    }
  }

  private void emitSingleLine(
      final String executionId,
      final String nodeId,
      final String line,
      final boolean stderr,
      @Nullable final LogLevel level) {
    // Log lines are best effort: when too many are waiting they are dropped and counted, so they
    // can never delay or displace a state change.
    if (pendingLogs.incrementAndGet() > MAX_PENDING_LOGS) {
      pendingLogs.decrementAndGet();
      telemetry.dropped(DROPPED_METRIC, "log");
      return;
    }
    publish(WorkflowLogEvent.create(executionId, nodeId, line, stderr, level));
  }

  /** Queues a state or log event; the queue is unbounded, so this only fails once terminated. */
  private void publish(final TrackerEvent event) {
    if (!SinkEmit.offer(eventSink, event)) {
      telemetry.dropped(DROPPED_METRIC, "state");
      log.atError()
          .addKeyValue("event", event.getClass().getSimpleName())
          .log("Tracker event lost");
    }
  }

  /**
   * Finish the workflow execution.
   *
   * @param executionId the execution identifier
   * @param status the final status
   * @return a Mono that completes when the workflow is finished
   */
  @Override
  public Mono<Void> finishWorkflow(
      @NotBlank final String executionId, @NotNull final TaskStatus status) {
    return Mono.fromRunnable(
        () -> {
          log.atInfo()
              .addKeyValue("executionId", executionId)
              .addKeyValue("status", status)
              .log("Finishing workflow execution");
          emitWorkflowStatusEvent(executionId, status);
        });
  }

  /**
   * Append a log line to the live output.
   *
   * @param executionId the execution identifier
   * @param line the log line
   * @return a Mono that completes when the log line is appended
   */
  @Override
  public Mono<Void> appendLog(
      @NotBlank final String executionId, @NotBlank @Size(max = 16_384) final String line) {
    return Mono.fromRunnable(() -> emitLogEvent(executionId, "system", line));
  }

  /**
   * Get the current progress of a workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return the workflow progress
   */
  @Override
  public WorkflowProgress getProgress(
      @SessionId final String sessionId, @NotBlank final String executionId) {
    final Map<String, WorkflowState> states = sessionStates.get(sessionId);
    WorkflowProgress progress = null;
    if (states != null) {
      final WorkflowState state = states.get(executionId);
      if (state != null) {
        progress =
            new WorkflowProgress(
                state.executionId,
                state.sessionId,
                state.workflowId,
                state.status,
                state.getTasks(),
                state.startTime,
                state.endTime,
                state.controlState,
                state.definitionVersion,
                state.parentExecutionId,
                state.rootExecutionId,
                state.restartOf);
      }
    }
    return progress;
  }

  /**
   * Get workflow progress by execution ID only (independent of session).
   *
   * <p>Used internally when only the execution ID is known, without session context.
   *
   * @param executionId the execution identifier
   * @return the workflow progress, or null if not found
   */
  @Override
  public WorkflowProgress getProgressByExecutionId(@NotBlank final String executionId) {
    final WorkflowState state = executionIndex.get(executionId);
    if (state == null) {
      return null;
    }
    return new WorkflowProgress(
        state.executionId,
        state.sessionId,
        state.workflowId,
        state.status,
        state.getTasks(),
        state.startTime,
        state.endTime,
        state.controlState,
        state.definitionVersion,
        state.parentExecutionId,
        state.rootExecutionId,
        state.restartOf);
  }

  /**
   * Get the latest execution ID for a workflow in a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return the execution identifier or null if none
   */
  @Override
  public String getLatestExecutionId(
      @SessionId final String sessionId, @WorkflowId final String workflowId) {
    return latestExecs.get(getLatestKey(sessionId, workflowId));
  }

  /**
   * Get the log stream for an execution with history and auto-termination.
   *
   * <p>Emits all logs from execution start, then continues with live logs as they arrive. Stream
   * auto-completes when execution reaches terminal state.
   *
   * @param executionId the execution identifier
   * @return the log flux that completes on workflow terminal state
   */
  @Override
  public Flux<String> getLogStream(@NotBlank final String executionId) {
    return streams.logStream(executionId);
  }

  /**
   * Get a Flux of all log entries across all executions.
   *
   * <p>Converts WorkflowLogEvent to PluginLogEntry with available context. Missing fields
   * (pluginId, pluginName, stream, logLevel) are populated with defaults or derived values.
   *
   * @return flux of plugin log entries
   */
  public Flux<PluginLogEntry> getLogFlux() {
    return streams.logFlux();
  }

  /**
   * Puts a run that ended before this server started back into history.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it ran
   * @param status its final status
   * @param nodeStatuses final status of each node
   * @param started when it started
   * @param ended when it ended
   * @param definitionVersion content hash of the graph the run used, or null if not recorded
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public void restore(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String status,
      final Map<String, String> nodeStatuses,
      final Instant started,
      final Instant ended,
      final String definitionVersion) {
    history.restore(
        executionId,
        sessionId,
        workflowId,
        status,
        nodeStatuses,
        started,
        ended,
        definitionVersion);
  }

  /**
   * Get history of executions for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing list of workflow execution summaries
   */
  @Override
  public Mono<List<WorkflowExecutionSummary>> getHistory(@SessionId final String sessionId) {
    return Mono.fromCallable(() -> history.summaries(sessionId, _ -> true));
  }

  /**
   * Get history of executions for a single workflow within a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing list of workflow execution summaries for the given workflow
   */
  @Override
  public Mono<List<WorkflowExecutionSummary>> getHistory(
      @SessionId final String sessionId, @WorkflowId final String workflowId) {
    return Mono.fromCallable(
        () -> history.summaries(sessionId, state -> workflowId.equals(state.workflowId)));
  }

  /**
   * List all active sessions being tracked.
   *
   * @return list of session IDs
   */
  @Override
  public List<String> getActiveSessions() {
    return List.copyOf(sessionStates.keySet());
  }

  /**
   * Remove tracking data for a session.
   *
   * @param sessionId the session identifier
   */
  @Override
  public void removeSession(@SessionId final String sessionId) {
    sessionUsed.remove(sessionId);
    history.forget(sessionId);
    final Map<String, WorkflowState> states = sessionStates.remove(sessionId);
    if (states != null) {
      final int executionCount = states.size();
      states
          .keySet()
          .forEach(
              execId -> {
                executionIndex.remove(execId);
                streams.drop(execId);
              });
      latestExecs.keySet().removeIf(key -> key.startsWith(sessionId + "\0"));
      log.atInfo()
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("executionsRemoved", executionCount)
          .log("Removed tracking data for session");
    } else {
      log.atDebug().addKeyValue("sessionId", sessionId).log("Session not found for removal");
    }
  }

  /**
   * Drops the session that started a run longest ago while there are more than {@code maxSessions},
   * never one with a run still going and never the session that just started.
   *
   * <p>Each pass scans every session and its runs, so a start costs O(sessions) while the limit is
   * exceeded; that is cheap for a small {@code maxSessions}, the intended use.
   */
  private void evictOldSessions(final String current) {
    while (maxSessions > 0 && sessionStates.size() > maxSessions) {
      final String oldest =
          sessionStates.entrySet().stream()
              .filter(e -> !e.getKey().equals(current))
              .filter(
                  e ->
                      e.getValue().values().stream()
                          .allMatch(state -> TaskStatus.isTerminalName(state.status)))
              .map(Map.Entry::getKey)
              .min(Comparator.comparingLong(id -> sessionUsed.getOrDefault(id, 0L)))
              .orElse(null);
      if (oldest == null) {
        return;
      }
      removeSession(oldest);
    }
  }

  /**
   * Drops every session that has no run going and has been idle longer than {@code
   * yukta.tracker.session-ttl}; does nothing when that is not set. A session is idle from the end
   * of its last run, or from its last start if that is newer.
   */
  /* default */ void evictExpiredSessions() {
    final Duration ttl = sessionTtl;
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      return;
    }
    final long now = clock.getAsLong();
    final long ttlNanos = ttl.toNanos();
    sessionStates.entrySet().stream()
        .filter(
            entry ->
                entry.getValue().values().stream()
                    .allMatch(state -> TaskStatus.isTerminalName(state.status)))
        // a session nobody has touched since startup counts as used now, so it gets a full TTL
        .filter(entry -> now - sessionUsed.computeIfAbsent(entry.getKey(), _ -> now) > ttlNanos)
        .map(Map.Entry::getKey)
        .toList()
        .forEach(this::removeSession);
  }

  /* default */ void setMaxSessions(final int maxSessions) {
    this.maxSessions = maxSessions;
  }

  /* default */ void setSessionTtl(final Duration ttl) {
    this.sessionTtl = ttl;
  }

  /* default */ void setClock(final LongSupplier clock) {
    this.clock = clock;
  }

  /** Applies a batch of events strictly in the order in which they were emitted. */
  /* default */ void handleEvents(final List<TrackerEvent> events) {
    applier.apply(events);
  }

  /**
   * Get the status stream for an execution.
   *
   * @param executionId the execution identifier
   * @return the status flux
   */
  @Override
  public Flux<WorkflowProgress> getStatusStream(@NotBlank final String executionId) {
    return getStatusStream(executionId, true);
  }

  /**
   * Get a flux of status updates for an execution with optional history.
   *
   * <p>If includeHistory is true, emits cached historical updates first, then chains to live
   * updates. Stream auto-completes when a terminal state is detected. Each live update is cached
   * for future clients.
   *
   * @param executionId the execution identifier
   * @param includeHistory if true, emit cached history before live updates
   * @return the status flux (history + live), or empty if execution not found
   */
  @Override
  public Flux<WorkflowProgress> getStatusStream(
      @NotBlank final String executionId, final boolean includeHistory) {
    return streams.statusStream(executionId, includeHistory);
  }

  private WorkflowState findState(final String executionId) {
    return executionIndex.get(executionId);
  }

  private String getLatestKey(final String sessionId, final String workflowId) {
    return sessionId + "\0" + workflowId;
  }

  private void scheduleCleanup(final String executionId) {
    Optional.ofNullable(executionIndex.get(executionId))
        .ifPresent(ended -> sessionUsed.put(ended.sessionId, clock.getAsLong()));
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("delayMs", cleanupTtl.toMillis())
        .log("Scheduled execution cleanup");
    Mono.delay(cleanupTtl).subscribe(_ -> cleanupExecution(executionId));
  }

  private void cleanupExecution(final String executionId) {
    log.atDebug().addKeyValue("executionId", executionId).log("Cleaning up execution data");
    streams.close(executionId);
    applier.forget(executionId);
    final WorkflowState state = executionIndex.remove(executionId);
    if (state != null) {
      history.trim(state);
    }
    log.atDebug().addKeyValue("executionId", executionId).log("Completed execution cleanup");
  }
}
