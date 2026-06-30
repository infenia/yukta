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
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.event.WorkflowLogEvent;
import com.infenia.yukta.event.WorkflowStatusEvent;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.validation.NodeId;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/** Service for tracking the progress of workflows and tasks. */
@Slf4j
@Service
@Validated
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.UseObjectForClearerAPI",
  "PMD.OnlyOneReturn",
  "PMD.AvoidDuplicateLiterals"
})
public class DefaultTaskTrackerService implements TaskTrackerService {

  /** Batch size for event processing. */
  private static final int BATCH_SIZE = 100;

  /** Batch timeout duration for event processing. */
  private static final Duration BATCH_TIMEOUT = Duration.ofMillis(50);

  /** Emit failure handler with retry logic. */
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100));

  /** Time to live for execution data after terminal status. */
  private final Duration cleanupTtl;

  /** Map of session ID to workflow states. */
  private final Map<String, Map<String, WorkflowState>> sessionStates = new ConcurrentHashMap<>();

  /** Map of execution ID to workflow state. */
  private final Map<String, WorkflowState> executionIndex = new ConcurrentHashMap<>();

  /** Map of workflow ID to latest execution ID. */
  private final Map<String, String> latestExecs = new ConcurrentHashMap<>();

  /** Map of workflow ID to log sinks. */
  private final Map<String, Sinks.Many<String>> logSinks = new ConcurrentHashMap<>();

  /** Map of workflow ID to status sinks. */
  private final Map<String, Sinks.Many<WorkflowProgress>> statusSinks = new ConcurrentHashMap<>();

  /** Sink for task status events. */
  private final Sinks.Many<TaskStatusEvent> taskStatusSink =
      Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

  /** Sink for workflow log events. */
  private final Sinks.Many<WorkflowLogEvent> logSink =
      Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE * 4, false);

  /** Sink for workflow status events. */
  private final Sinks.Many<WorkflowStatusEvent> wfStatusSink =
      Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

  /**
   * Create a new DefaultTaskTrackerService with configurable cleanup TTL.
   *
   * @param cleanupTtl the time to live for execution data after terminal status
   */
  public DefaultTaskTrackerService(
      @Value("${yukta.tracker.cleanup-ttl:10m}") final Duration cleanupTtl) {
    this.cleanupTtl = cleanupTtl;
  }

  /** Initialize background event consumers. */
  @PostConstruct
  public void init() {
    log.atInfo()
        .addKeyValue("cleanupTtl", cleanupTtl)
        .addKeyValue("batchSize", BATCH_SIZE)
        .addKeyValue("batchTimeoutMs", BATCH_TIMEOUT.toMillis())
        .log("Initialized DefaultTaskTrackerService with event consumers");

    // Status Event Consumer
    taskStatusSink
        .asFlux()
        .publishOn(Schedulers.parallel())
        .bufferTimeout(BATCH_SIZE, BATCH_TIMEOUT)
        .concatMap(
            batch ->
                Mono.fromRunnable(() -> handleTaskStatusEvents(batch))
                    .onErrorResume(
                        e -> {
                          log.atError()
                              .addKeyValue("batchSize", batch.size())
                              .log("Error processing task status event batch", e);
                          return Mono.empty();
                        }))
        .subscribe();

    // Workflow Status Event Consumer
    wfStatusSink
        .asFlux()
        .publishOn(Schedulers.parallel())
        .bufferTimeout(BATCH_SIZE, BATCH_TIMEOUT)
        .concatMap(
            batch ->
                Mono.fromRunnable(() -> handleWorkflowStatusEvents(batch))
                    .onErrorResume(
                        e -> {
                          log.atError()
                              .addKeyValue("batchSize", batch.size())
                              .log("Error processing workflow status event batch", e);
                          return Mono.empty();
                        }))
        .subscribe();

    // Log Event Consumer
    logSink
        .asFlux()
        .publishOn(Schedulers.parallel())
        .bufferTimeout(BATCH_SIZE, BATCH_TIMEOUT)
        .concatMap(
            batch ->
                Mono.fromRunnable(() -> handleLogEvents(batch))
                    .onErrorResume(
                        e -> {
                          log.atError()
                              .addKeyValue("batchSize", batch.size())
                              .log("Error processing log event batch", e);
                          return Mono.empty();
                        }))
        .subscribe();
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
      @NotBlank @Size(max = 256) final String status) {
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
      @NotBlank @Size(max = 256) final String status,
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
      @NotBlank @Size(max = 256) final String status,
      @NotNull final Map<String, Object> metadata) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("nodeId", nodeId)
        .addKeyValue("module", module)
        .addKeyValue("status", status)
        .addKeyValue("metadataSize", metadata.size())
        .log("Emitting task status event");
    taskStatusSink.emitNext(
        TaskStatusEvent.create(executionId, nodeId, module, status, metadata), RETRY_HANDLER);
  }

  /**
   * Emit a workflow status event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param status the final status
   */
  @Override
  public void emitWorkflowStatusEvent(
      @NotBlank final String executionId, @NotBlank @Size(max = 256) final String status) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("status", status)
        .log("Emitting workflow status event");
    wfStatusSink.emitNext(WorkflowStatusEvent.create(executionId, status), RETRY_HANDLER);
  }

  /**
   * Emit a log event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param line the log line
   */
  @Override
  public void emitLogEvent(
      @NotBlank final String executionId, @NotBlank @Size(max = 16_384) final String line) {
    log.atTrace()
        .addKeyValue("executionId", executionId)
        .addKeyValue("lineLength", line.length())
        .log("Emitting workflow log event");
    logSink.emitNext(WorkflowLogEvent.create(executionId, line), RETRY_HANDLER);
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
      @NotBlank final String executionId, @NotBlank @Size(max = 256) final String status) {
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
    return Mono.fromRunnable(() -> emitLogEvent(executionId, line));
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
                state.endTime);
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
        state.endTime);
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
   * Get the log stream for an execution.
   *
   * @param executionId the execution identifier
   * @return the log flux
   */
  @Override
  public Flux<String> getLogStream(@NotBlank final String executionId) {
    final Sinks.Many<String> sink = logSinks.get(executionId);
    return sink != null ? sink.asFlux() : Flux.empty();
  }

  /**
   * Get history of executions for a session.
   *
   * @param sessionId the session identifier
   * @return list of workflow execution summaries
   */
  @Override
  public List<WorkflowExecutionSummary> getHistory(@SessionId final String sessionId) {
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

  private void handleTaskStatusEvents(final List<TaskStatusEvent> events) {
    final Set<String> execIds = new HashSet<>();
    for (final TaskStatusEvent event : events) {
      final WorkflowState state = findState(event.executionId());
      if (state != null) {
        state.updateTask(event.nodeId(), event.module(), event.status(), event.metadata());
        execIds.add(event.executionId());
      } else {
        log.atDebug()
            .addKeyValue("executionId", event.executionId())
            .addKeyValue("nodeId", event.nodeId())
            .log("Received task status event for unknown execution");
      }
    }
    if (!execIds.isEmpty()) {
      log.atDebug()
          .addKeyValue("eventsProcessed", events.size())
          .addKeyValue("executionsUpdated", execIds.size())
          .log("Processed task status event batch");
    }
    execIds.forEach(this::notifyStatusChange);
  }

  private void handleWorkflowStatusEvents(final List<WorkflowStatusEvent> events) {
    final Set<String> execIds = new HashSet<>();
    int terminalCount = 0;
    for (final WorkflowStatusEvent event : events) {
      final WorkflowState state = findState(event.executionId());
      if (state != null) {
        state.setStatus(event.status());
        state.setEndTime(event.timestamp());
        execIds.add(event.executionId());

        if (isTerminal(event.status())) {
          scheduleCleanup(event.executionId());
          terminalCount++;
          log.atDebug()
              .addKeyValue("executionId", event.executionId())
              .addKeyValue("finalStatus", event.status())
              .log("Workflow reached terminal status, scheduled cleanup");
        }
      } else {
        log.atWarn()
            .addKeyValue("executionId", event.executionId())
            .log("Received workflow status event for unknown execution");
      }
    }
    if (!execIds.isEmpty()) {
      log.atDebug()
          .addKeyValue("eventsProcessed", events.size())
          .addKeyValue("executionsUpdated", execIds.size())
          .addKeyValue("terminalTransitions", terminalCount)
          .log("Processed workflow status event batch");
    }
    execIds.forEach(this::notifyStatusChange);
  }

  private void handleLogEvents(final List<WorkflowLogEvent> events) {
    int emittedCount = 0;
    int missedCount = 0;
    for (final WorkflowLogEvent event : events) {
      final Sinks.Many<String> sink = logSinks.get(event.executionId());
      if (sink != null) {
        sink.tryEmitNext(event.line());
        emittedCount++;
      } else {
        missedCount++;
      }
    }
    if (!events.isEmpty()) {
      log.atDebug()
          .addKeyValue("eventsProcessed", events.size())
          .addKeyValue("emittedCount", emittedCount)
          .addKeyValue("missedCount", missedCount)
          .log("Processed workflow log event batch");
    }
  }

  private void notifyStatusChange(final String executionId) {
    final Sinks.Many<WorkflowProgress> sink = statusSinks.get(executionId);
    if (sink != null) {
      final WorkflowState state = findState(executionId);
      if (state != null) {
        sink.tryEmitNext(getProgress(state.sessionId, executionId));
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
    final Sinks.Many<WorkflowProgress> sink = statusSinks.get(executionId);
    return sink != null ? sink.asFlux() : Flux.empty();
  }

  private WorkflowState findState(final String executionId) {
    return executionIndex.get(executionId);
  }

  private String getLatestKey(final String sessionId, final String workflowId) {
    return sessionId + "\0" + workflowId;
  }

  private boolean isTerminal(final String status) {
    return TaskStatus.valueOf(status).isTerminal();
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
    executionIndex.remove(executionId);
    log.atDebug().addKeyValue("executionId", executionId).log("Completed execution cleanup");
  }

  /** Tracks the state of a workflow execution. */
  private static final class WorkflowState {
    /** The execution identifier. */
    private final String executionId;

    /** The session identifier. */
    private final String sessionId;

    /** The workflow identifier. */
    private final String workflowId;

    /** The current execution status. */
    private String status;

    /** Map of task progress by node ID. */
    private final Map<String, TaskProgress> taskMap;

    /** The start time of the execution. */
    private final LocalDateTime startTime;

    /** The end time of the execution. */
    private LocalDateTime endTime;

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
            return createUpdatedTask(old, module, status, metadata);
          });
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
      final TaskStatus taskStatus = TaskStatus.valueOf(status);
      return (taskStatus.isTerminal() && current == null)
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

    /* default */ void setEndTime(final LocalDateTime endTime) {
      this.endTime = endTime;
    }
  }
}
