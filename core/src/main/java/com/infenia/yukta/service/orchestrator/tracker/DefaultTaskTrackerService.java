// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.event.TrackerEvent;
import com.infenia.yukta.event.WorkflowLogEvent;
import com.infenia.yukta.event.WorkflowStatusEvent;
import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.streaming.StatusHistoryCache;
import com.infenia.yukta.util.SinkEmit;
import com.infenia.yukta.validation.NodeId;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Service for tracking the progress of workflows and tasks. */
@Slf4j
@Service
@Validated
@SuppressWarnings({
  "PMD.CyclomaticComplexity",
  "PMD.ExcessiveImports",
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.OnlyOneReturn",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LawOfDemeter"
})
@SuppressFBWarnings("EI2")
public class DefaultTaskTrackerService implements TaskTrackerService {

  /** Batch size for event processing. */
  private static final int BATCH_SIZE = 100;

  /** Batch timeout duration for event processing. */
  private static final Duration BATCH_TIMEOUT = Duration.ofMillis(50);

  /** Completes a sink, retrying only while another thread is emitting at the same moment. */
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100));

  /** Finished executions kept per session for history; older ones are dropped on cleanup. */
  /* default */ static final int MAX_FINISHED_PER_SESSION = 100;

  /** Most log events that may wait to be applied before further ones are dropped. */
  private static final int MAX_PENDING_LOGS = 10_000;

  /** Metric counting events the tracker could not queue. */
  private static final String DROPPED_METRIC = "yukta.tracker.dropped";

  /** Time to live for execution data after terminal status. */
  private final Duration cleanupTtl;

  /** Registry for the node metrics. */
  private final MeterRegistry meters;

  /** Registry that turns node runs into observations (spans, when a tracer is configured). */
  private final ObservationRegistry observations;

  /** Observations of runs that have not ended, by execution; parents of the node observations. */
  private final Map<String, Observation> runSpans = new ConcurrentHashMap<>();

  /** Observations of nodes that are running, by execution and node. */
  private final Map<String, Observation> nodeSpans = new ConcurrentHashMap<>();

  /** Cache for storing recent workflow progress updates per execution. */
  private final StatusHistoryCache statusHistoryCache;

  /** Durable record of run starts and ends. */
  private final ExecutionLogStore executionLog;

  /** Map of session ID to workflow states. */
  private final Map<String, Map<String, WorkflowState>> sessionStates = new ConcurrentHashMap<>();

  /** Per session, execution IDs that were cleaned up, oldest first. */
  private final Map<String, Deque<String>> finishedBySession = new ConcurrentHashMap<>();

  /** Map of execution ID to workflow state. */
  private final Map<String, WorkflowState> executionIndex = new ConcurrentHashMap<>();

  /** Map of workflow ID to latest execution ID. */
  private final Map<String, String> latestExecs = new ConcurrentHashMap<>();

  /** Map of workflow ID to log sinks. */
  private final Map<String, Sinks.Many<String>> logSinks = new ConcurrentHashMap<>();

  /** Map of workflow ID to status sinks. */
  private final Map<String, Sinks.Many<WorkflowProgress>> statusSinks = new ConcurrentHashMap<>();

  /**
   * Sink for every tracker event. A single ordered stream guarantees that a node's last status is
   * applied before the workflow's terminal status, and that log lines keep their place.
   */
  private final Sinks.Many<TrackerEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

  /** Log events accepted but not yet consumed; bounds how much a chatty plugin can queue. */
  private final AtomicInteger pendingLogs = new AtomicInteger();

  /** Best-effort feed of log events for {@link #getLogFlux()}; slow readers never block events. */
  private final Sinks.Many<WorkflowLogEvent> logFeed = Sinks.many().multicast().directBestEffort();

  /**
   * Create a new DefaultTaskTrackerService with configurable cleanup TTL.
   *
   * @param cleanupTtl the time to live for execution data after terminal status
   * @param statusHistoryCache the cache for storing recent workflow progress updates
   * @param executionLog durable log of run starts and ends
   * @param meters registry for the node metrics
   * @param observations registry for the node observations; each node run is one observation named
   *     {@code yukta.node}
   */
  @Autowired
  public DefaultTaskTrackerService(
      @Value("${yukta.tracker.cleanup-ttl:10m}") final Duration cleanupTtl,
      final StatusHistoryCache statusHistoryCache,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final ObservationRegistry observations) {
    this.cleanupTtl = cleanupTtl;
    this.statusHistoryCache = statusHistoryCache;
    this.executionLog = executionLog;
    this.meters = meters;
    this.observations = observations;
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
        .subscribe(
            _ -> {},
            e -> log.atError().setCause(e).log("Tracker event consumer stopped unexpectedly"));
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
          runSpans.put(
              executionId,
              Observation.createNotStarted("yukta.run", observations)
                  .contextualName("run " + workflowId)
                  .highCardinalityKeyValue("executionId", executionId)
                  .highCardinalityKeyValue("sessionId", sessionId)
                  .highCardinalityKeyValue("workflowId", workflowId)
                  .start());

          latestExecs.put(getLatestKey(sessionId, workflowId), executionId);

          final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
          logSinks.put(executionId, sink);

          final Sinks.Many<WorkflowProgress> statusSink =
              Sinks.many().multicast().directBestEffort();
          statusSinks.put(executionId, statusSink);

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
    notifyStatusChange(executionId);
  }

  @Override
  public void recordRetry(final String module) {
    meters.counter("yukta.node.retries", "module", module).increment();
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
    log.atTrace()
        .addKeyValue("executionId", executionId)
        .addKeyValue("nodeId", nodeId)
        .addKeyValue("lineLength", line.length())
        .log("Emitting workflow log event");
    // Log lines are best effort: when too many are waiting they are dropped and counted, so they
    // can never delay or displace a state change.
    if (pendingLogs.incrementAndGet() > MAX_PENDING_LOGS) {
      pendingLogs.decrementAndGet();
      meters.counter(DROPPED_METRIC, "type", "log").increment();
      return;
    }
    publish(WorkflowLogEvent.create(executionId, nodeId, line));
  }

  /** Queues a state or log event; the queue is unbounded, so this only fails once terminated. */
  private void publish(final TrackerEvent event) {
    if (!SinkEmit.offer(eventSink, event)) {
      meters.counter(DROPPED_METRIC, "type", "state").increment();
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
                state.definitionVersion);
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
        state.definitionVersion);
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
    log.atDebug().addKeyValue("executionId", executionId).log("Getting log stream");

    final Sinks.Many<String> sink = logSinks.get(executionId);
    if (sink == null) {
      log.atWarn().addKeyValue("executionId", executionId).log("No log sink found for execution");
      return Flux.empty();
    }

    final Flux<String> logFlux = sink.asFlux();

    return logFlux
        .takeUntil(
            _ -> {
              final WorkflowState state = findState(executionId);
              if (state != null && isTerminal(state.status) && state.endTime != null) {
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("status", state.status)
                    .log("Workflow terminal status detected, terminating log stream");
                return true;
              }
              return false;
            })
        .doOnComplete(
            () -> log.atInfo().addKeyValue("executionId", executionId).log("Log stream completed"));
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
    return logFeed
        .asFlux()
        .map(
            event -> {
              final WorkflowState state = findState(event.executionId());
              final String sessionId = state != null ? state.sessionId : "unknown";
              final String module =
                  state != null && state.taskMap.get(event.nodeId()) != null
                      ? state.taskMap.get(event.nodeId()).module()
                      : "unknown";

              return new PluginLogEntry(
                  event.executionId(),
                  sessionId,
                  event.nodeId(), // Use nodeId as pluginId
                  module, // Use module as pluginName
                  LogStream.STDOUT, // Default to STDOUT
                  event.line(),
                  LogLevel.INFO, // Default to INFO
                  event.timestamp().atZone(ZoneId.systemDefault()).toInstant(),
                  null,
                  null);
            });
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
    final WorkflowState state =
        new WorkflowState(
            executionId,
            sessionId,
            workflowId,
            status,
            List.copyOf(nodeStatuses.keySet()),
            LocalDateTime.ofInstant(started, ZoneId.systemDefault()));
    nodeStatuses.forEach(
        (nodeId, nodeStatus) -> state.updateTask(nodeId, "", nodeStatus, Map.of()));
    state.setEndTime(LocalDateTime.ofInstant(ended, ZoneId.systemDefault()));
    state.definitionVersion = definitionVersion;
    sessionStates
        .computeIfAbsent(sessionId, _ -> new ConcurrentHashMap<>())
        .put(executionId, state);
    executionIndex.put(executionId, state);
  }

  /**
   * Get history of executions for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing list of workflow execution summaries
   */
  @Override
  public Mono<List<WorkflowExecutionSummary>> getHistory(@SessionId final String sessionId) {
    return Mono.fromCallable(
        () -> {
          final Map<String, WorkflowState> states = sessionStates.get(sessionId);
          List<WorkflowExecutionSummary> history = Collections.emptyList();
          if (states != null) {
            history =
                states.values().stream()
                    .map(
                        s ->
                            new WorkflowExecutionSummary(
                                s.executionId, s.workflowId, s.status, s.startTime, s.endTime))
                    .sorted((a, b) -> b.startTime().compareTo(a.startTime()))
                    .toList();
          }
          return history;
        });
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
        () -> {
          final Map<String, WorkflowState> states = sessionStates.get(sessionId);
          List<WorkflowExecutionSummary> history = Collections.emptyList();
          if (states != null) {
            history =
                states.values().stream()
                    .filter(s -> workflowId.equals(s.workflowId))
                    .map(
                        s ->
                            new WorkflowExecutionSummary(
                                s.executionId, s.workflowId, s.status, s.startTime, s.endTime))
                    .sorted((a, b) -> b.startTime().compareTo(a.startTime()))
                    .toList();
          }
          return history;
        });
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
    finishedBySession.remove(sessionId);
    final Map<String, WorkflowState> states = sessionStates.remove(sessionId);
    if (states != null) {
      final int executionCount = states.size();
      states
          .keySet()
          .forEach(
              execId -> {
                executionIndex.remove(execId);
                logSinks.remove(execId);
                statusSinks.remove(execId);
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

  /** Applies a batch of events strictly in the order in which they were emitted. */
  private void handleEvents(final List<TrackerEvent> events) {
    final Set<String> touched = new LinkedHashSet<>();
    for (final TrackerEvent event : events) {
      switch (event) {
        case TaskStatusEvent task -> applyTask(task, touched);
        case WorkflowStatusEvent workflow -> applyWorkflow(workflow, touched);
        case WorkflowLogEvent logEvent -> applyLog(logEvent);
      }
    }
    log.atDebug()
        .addKeyValue("eventsProcessed", events.size())
        .addKeyValue("executionsUpdated", touched.size())
        .log("Processed tracker event batch");
    touched.forEach(this::notifyStatusChange);
  }

  private void applyTask(final TaskStatusEvent event, final Set<String> touched) {
    final WorkflowState state = findState(event.executionId());
    if (state == null) {
      log.atDebug()
          .addKeyValue("executionId", event.executionId())
          .addKeyValue("nodeId", event.nodeId())
          .log("Received task status event for unknown execution");
      return;
    }
    state.updateTask(event.nodeId(), event.module(), event.status(), event.metadata());
    if (state.taskMap.containsKey(event.nodeId())) {
      traceNode(event);
    }
    if (isTerminal(event.status())) {
      recordNode(state.taskMap.get(event.nodeId()));
    }
    if (TaskStatus.SUCCESS.name().equals(event.status())) {
      executionLog.append(
          ExecutionLogEvent.nodeFinished(
              state.executionId, state.sessionId, state.workflowId, event.nodeId()));
    }
    touched.add(event.executionId());
  }

  /** Stops observations of nodes that were finalized with the run instead of ending themselves. */
  private void closeNodeSpans(final String executionId) {
    final String prefix = executionId + "\0";
    nodeSpans
        .keySet()
        .removeIf(
            key -> {
              if (!key.startsWith(prefix)) {
                return false;
              }
              final Observation span = nodeSpans.get(key);
              span.lowCardinalityKeyValue("status", TaskStatus.CANCELLED.name());
              span.stop();
              return true;
            });
  }

  /** Opens an observation when a node starts running and closes it when the node ends. */
  private void traceNode(final TaskStatusEvent event) {
    final String key = event.executionId() + "\0" + event.nodeId();
    if (TaskStatus.RUNNING.name().equals(event.status())) {
      nodeSpans.computeIfAbsent(
          key,
          _ ->
              Observation.createNotStarted("yukta.node", observations)
                  .parentObservation(runSpans.get(event.executionId()))
                  .contextualName("node " + event.nodeId())
                  .lowCardinalityKeyValue("module", String.valueOf(event.module()))
                  .highCardinalityKeyValue("executionId", event.executionId())
                  .highCardinalityKeyValue("nodeId", event.nodeId())
                  .start());
    } else if (isTerminal(event.status())) {
      final Observation span = nodeSpans.remove(key);
      if (span != null) {
        span.lowCardinalityKeyValue("status", event.status());
        span.stop();
      }
    }
  }

  /** Counts the node outcome by plugin and status, and times nodes that actually ran. */
  private void recordNode(final TaskProgress task) {
    if (task == null) {
      return;
    }
    meters
        .counter("yukta.node.executions", "module", task.module(), "status", task.status())
        .increment();
    if (task.startTime() != null && task.endTime() != null) {
      meters
          .timer("yukta.node.duration", "module", task.module())
          .record(Duration.between(task.startTime(), task.endTime()));
    }
  }

  private void applyWorkflow(final WorkflowStatusEvent event, final Set<String> touched) {
    final WorkflowState state = findState(event.executionId());
    if (state == null) {
      log.atWarn()
          .addKeyValue("executionId", event.executionId())
          .log("Received workflow status event for unknown execution");
      return;
    }
    if (TaskStatus.QUEUED.name().equals(event.status())) {
      state.queuedAt = System.nanoTime();
    } else if (TaskStatus.RUNNING.name().equals(event.status()) && state.queuedAt != 0) {
      meters
          .timer("yukta.execution.queue.wait")
          .record(Duration.ofNanos(System.nanoTime() - state.queuedAt));
      state.queuedAt = 0;
    }
    if (isTerminal(event.status())) {
      // Every node event before this one is already applied, so what is still open is final.
      state.finalizeTasks(event.status());
      closeNodeSpans(event.executionId());
      state.setEndTime(event.timestamp());
      meters
          .timer("yukta.execution.duration", "status", event.status())
          .record(Duration.between(state.startTime, event.timestamp()));
      final Observation run = runSpans.remove(event.executionId());
      if (run != null) {
        run.lowCardinalityKeyValue("status", event.status());
        run.stop();
      }
      executionLog.append(
          ExecutionLogEvent.finished(
              state.executionId,
              state.sessionId,
              state.workflowId,
              event.status(),
              state.nodeStatuses()));
    }
    state.setStatus(event.status());
    touched.add(event.executionId());
    if (isTerminal(event.status())) {
      scheduleCleanup(event.executionId());
      log.atDebug()
          .addKeyValue("executionId", event.executionId())
          .addKeyValue("finalStatus", event.status())
          .log("Workflow reached terminal status, scheduled cleanup");
    }
  }

  private void applyLog(final WorkflowLogEvent event) {
    pendingLogs.decrementAndGet();
    logFeed.tryEmitNext(event);
    final Sinks.Many<String> sink = logSinks.get(event.executionId());
    if (sink != null) {
      sink.tryEmitNext(event.line());
    }
  }

  private void notifyStatusChange(final String executionId) {
    final Sinks.Many<WorkflowProgress> sink = statusSinks.get(executionId);
    if (sink != null) {
      final WorkflowState state = findState(executionId);
      if (state != null) {
        final WorkflowProgress progress = getProgress(state.sessionId, executionId);
        statusHistoryCache.put(executionId, progress);
        SinkEmit.offer(sink, progress);
      }
    }
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
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("includeHistory", includeHistory)
        .log("Getting status stream");

    final Sinks.Many<WorkflowProgress> sink = statusSinks.get(executionId);
    if (sink == null) {
      log.atWarn()
          .addKeyValue("executionId", executionId)
          .log("No status sink found for execution");
      return Flux.empty();
    }

    Flux<WorkflowProgress> statusFlux = sink.asFlux();

    if (includeHistory) {
      final List<WorkflowProgress> history = statusHistoryCache.get(executionId);
      log.atDebug()
          .addKeyValue("executionId", executionId)
          .addKeyValue("historySize", history.size())
          .log("Including history in stream");
      statusFlux = Flux.fromIterable(history).concatWith(statusFlux);
    }

    return statusFlux
        .takeUntil(
            progress -> {
              final boolean isTerminal = isWorkflowTerminal(progress);
              if (isTerminal) {
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("status", progress.status())
                    .log("Workflow terminal status detected, terminating stream");
              }
              return isTerminal;
            })
        .doOnComplete(
            () ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .log("Status stream completed"));
  }

  private WorkflowState findState(final String executionId) {
    return executionIndex.get(executionId);
  }

  private String getLatestKey(final String sessionId, final String workflowId) {
    return sessionId + "\0" + workflowId;
  }

  private boolean isTerminal(final String status) {
    return isTerminalStatus(status);
  }

  private static boolean isTerminalStatus(final String status) {
    return TaskStatus.isTerminalName(status);
  }

  /**
   * Check if a workflow progress represents a terminal state.
   *
   * <p>Both conditions must be true: status must be terminal AND endTime must be set.
   *
   * @param progress the workflow progress to check
   * @return true if terminal state detected, false otherwise
   */
  private boolean isWorkflowTerminal(final WorkflowProgress progress) {
    final String status = progress.status();
    final LocalDateTime endTime = progress.endTime();

    final boolean isTerminalStatus = isTerminal(status);
    final boolean hasEndTime = endTime != null;

    if (isTerminalStatus && hasEndTime) {
      log.atDebug()
          .addKeyValue("status", status)
          .addKeyValue("endTime", endTime)
          .log("Workflow reached terminal state");
      return true;
    }

    return false;
  }

  private void scheduleCleanup(final String executionId) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("delayMs", cleanupTtl.toMillis())
        .log("Scheduled execution cleanup");
    Mono.delay(cleanupTtl)
        .subscribe(
            ignored -> cleanupExecution(executionId),
            error ->
                log.atError()
                    .addKeyValue("executionId", executionId)
                    .log("Error during cleanup scheduling", error));
  }

  private void cleanupExecution(final String executionId) {
    log.atDebug().addKeyValue("executionId", executionId).log("Cleaning up execution data");
    final Sinks.Many<String> logSink = logSinks.remove(executionId);
    if (logSink != null) {
      logSink.emitComplete(RETRY_HANDLER);
    }
    final Sinks.Many<WorkflowProgress> statusSink = statusSinks.remove(executionId);
    if (statusSink != null) {
      statusSink.emitComplete(RETRY_HANDLER);
    }
    final WorkflowState state = executionIndex.remove(executionId);
    if (state != null) {
      trimHistory(state);
    }
    log.atDebug().addKeyValue("executionId", executionId).log("Completed execution cleanup");
  }

  /** Remembers a cleaned-up execution and drops the oldest ones beyond the per-session cap. */
  @SuppressWarnings("PMD.AvoidSynchronizedStatement")
  private void trimHistory(final WorkflowState state) {
    final Map<String, WorkflowState> states = sessionStates.get(state.sessionId);
    if (states == null) {
      return;
    }
    final Deque<String> finished =
        finishedBySession.computeIfAbsent(state.sessionId, _ -> new ArrayDeque<>());
    // Cleanups of one session can run concurrently, so the queue is guarded by its own lock.
    synchronized (finished) {
      finished.addLast(state.executionId);
      while (finished.size() > MAX_FINISHED_PER_SESSION) {
        states.remove(finished.removeFirst());
      }
    }
  }

  /** Tracks the state of a workflow execution; written by the event thread, read by requests. */
  @SuppressWarnings("PMD.AvoidUsingVolatile")
  private static final class WorkflowState {
    /** The execution identifier. */
    private final String executionId;

    /** The session identifier. */
    private final String sessionId;

    /** The workflow identifier. */
    private final String workflowId;

    /** The current execution status. */
    private volatile String status;

    /** The latest operator control state, or null. */
    private volatile String controlState;

    /** The definition version (graph hash) this run uses, or null until recorded. */
    private volatile String definitionVersion;

    /** Map of task progress by node ID. */
    private final Map<String, TaskProgress> taskMap;

    /** The start time of the execution. */
    private final LocalDateTime startTime;

    /** The end time of the execution. */
    private volatile LocalDateTime endTime;

    /** When the run began waiting for a slot (System.nanoTime), or 0 if it is not waiting. */
    private volatile long queuedAt;

    /* default */ WorkflowState(
        final String executionId,
        final String sessionId,
        final String workflowId,
        final String status,
        final List<String> nodeIds,
        final LocalDateTime startTime) {
      this.executionId = executionId;
      this.sessionId = sessionId;
      this.workflowId = workflowId;
      this.status = status;
      this.taskMap = Collections.synchronizedMap(new LinkedHashMap<>(nodeIds.size()));
      nodeIds.forEach(
          nodeId ->
              taskMap.put(
                  nodeId,
                  new TaskProgress(nodeId, "", "PENDING", null, null, Collections.emptyMap())));
      this.startTime = startTime;
    }

    /* default */ void updateTask(
        final String nodeId,
        final String module,
        final String status,
        final Map<String, Object> metadata) {
      taskMap.compute(
          nodeId,
          (_, old) -> {
            if (old == null) {
              return null;
            }
            // A node only moves forward: a late event never reopens an ended node or steps back,
            // though what it reports is still merged.
            return createUpdatedTask(
                old, module, mayMoveTo(old.status(), status) ? status : old.status(), metadata);
          });
    }

    /** Whether a node in status {@code current} may be reported as {@code next}. */
    /* default */ static boolean mayMoveTo(final String current, final String next) {
      return current.equals(next)
          || (!TaskStatus.isTerminalName(current) && rank(next) >= rank(current));
    }

    private static int rank(final String status) {
      return switch (status) {
        case "PENDING" -> 0;
        case "QUEUED" -> 1;
        case "RUNNING" -> 2;
        default -> 3;
      };
    }

    /**
     * Gives every node that has not reached a terminal status its final one, once the run has
     * ended. A running node was cancelled; a node that never started was skipped when the run
     * succeeded, never reached when it failed, and cancelled otherwise.
     */
    /* default */ void finalizeTasks(final String runStatus) {
      final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
      taskMap.replaceAll(
          (_, task) -> {
            if (isTerminalStatus(task.status())) {
              return task;
            }
            final TaskStatus finalStatus = finalStatusFor(task.status(), runStatus);
            return new TaskProgress(
                task.nodeId(),
                task.module(),
                finalStatus.name(),
                task.startTime(),
                now,
                task.metadata());
          });
    }

    private static TaskStatus finalStatusFor(final String taskStatus, final String runStatus) {
      if (!TaskStatus.PENDING.name().equals(taskStatus)) {
        return TaskStatus.CANCELLED;
      }
      return switch (TaskStatus.valueOf(runStatus)) {
        case SUCCESS -> TaskStatus.SKIPPED;
        case FAILURE, ERROR, TIMED_OUT -> TaskStatus.UPSTREAM_FAILED;
        default -> TaskStatus.CANCELLED;
      };
    }

    /* default */ Map<String, String> nodeStatuses() {
      final Map<String, String> statuses = new ConcurrentHashMap<>();
      taskMap.forEach((nodeId, task) -> statuses.put(nodeId, task.status()));
      return statuses;
    }

    /* default */ List<TaskProgress> getTasks() {
      return List.copyOf(taskMap.values());
    }

    private TaskProgress createUpdatedTask(
        final TaskProgress current,
        final String module,
        final String status,
        final Map<String, Object> metadata) {
      final LocalDateTime taskStartTime = determineStartTime(current.startTime(), status);
      final LocalDateTime taskEndTime = determineEndTime(current.endTime(), status);
      final Map<String, Object> newMetadata = mergeMetadata(current.metadata(), metadata);

      return new TaskProgress(
          current.nodeId(), module, status, taskStartTime, taskEndTime, Map.copyOf(newMetadata));
    }

    private LocalDateTime determineStartTime(final LocalDateTime current, final String status) {
      return ("RUNNING".equals(status) && current == null)
          ? LocalDateTime.now(ZoneId.systemDefault())
          : current;
    }

    private LocalDateTime determineEndTime(final LocalDateTime current, final String status) {
      return (isTerminalStatus(status) && current == null)
          ? LocalDateTime.now(ZoneId.systemDefault())
          : current;
    }

    private Map<String, Object> mergeMetadata(
        final Map<String, Object> current, final Map<String, Object> additional) {
      final Map<String, Object> merged = new ConcurrentHashMap<>(current);
      if (additional != null) {
        merged.putAll(additional);
      }
      return merged;
    }

    /* default */ void setStatus(final String status) {
      this.status = status;
    }

    /* default */ void setControlState(final String controlState) {
      this.controlState = controlState;
    }

    /* default */ void setEndTime(final LocalDateTime endTime) {
      this.endTime = endTime;
    }
  }
}
