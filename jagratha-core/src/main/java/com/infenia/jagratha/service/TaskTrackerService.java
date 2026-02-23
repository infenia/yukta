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

import com.infenia.jagratha.model.TaskProgress;
import com.infenia.jagratha.model.WorkflowProgress;
import com.infenia.jagratha.validation.NodeId;
import com.infenia.jagratha.validation.SessionId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Service for tracking the progress of workflows and tasks. */
@Service
@Validated
public class TaskTrackerService {

  private final Map<String, WorkflowState> states = new ConcurrentHashMap<>();
  private final Map<String, Sinks.Many<String>> logSinks = new ConcurrentHashMap<>();
  private final Map<String, Sinks.Many<String>> statusSinks = new ConcurrentHashMap<>();

  /** Default constructor. */
  public TaskTrackerService() {
    // Standard service initialization
  }

  /**
   * Start tracking a new workflow for a session.
   *
   * @param sessionId the session identifier
   * @param nodeIds the list of node IDs in the workflow DAG
   * @return a Mono that completes when the workflow tracking is started
   */
  public Mono<Void> startWorkflow(
      @SessionId final String sessionId, @NotEmpty final List<String> nodeIds) {
    return Mono.fromRunnable(
        () -> {
          final List<TaskProgress> initialTasks =
              nodeIds.stream()
                  .map(id -> new TaskProgress(id, "", "PENDING", null, null, Map.of()))
                  .toList();
          states.put(
              sessionId,
              new WorkflowState(
                  sessionId, "RUNNING", new ArrayList<>(initialTasks), LocalDateTime.now()));

          final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
          logSinks.put(sessionId, sink);

          final Sinks.Many<String> statusSink = Sinks.many().multicast().directBestEffort();
          statusSinks.put(sessionId, statusSink);
        });
  }

  /**
   * Update the status of a specific node.
   *
   * @param sessionId the session identifier
   * @param nodeId the ID of the node
   * @param module the module name
   * @param status the new status
   * @return a Mono that completes when the task status is updated
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<Void> updateTaskStatus(
      @SessionId final String sessionId,
      @NodeId final String nodeId,
      @NotBlank @Size(max = 256) final String module,
      @NotBlank @Size(max = 256) final String status) {
    return updateTaskStatus(sessionId, nodeId, module, status, Map.of());
  }

  /**
   * Update the status and metadata of a specific node.
   *
   * @param sessionId the session identifier
   * @param nodeId the ID of the node
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   * @return a Mono that completes when the task status is updated
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<Void> updateTaskStatus(
      @SessionId final String sessionId,
      @NodeId final String nodeId,
      @NotBlank @Size(max = 256) final String module,
      @NotBlank @Size(max = 256) final String status,
      @NotNull final Map<String, Object> metadata) {
    return Mono.fromRunnable(
        () -> {
          final WorkflowState state = states.get(sessionId);
          if (state != null) {
            state.updateTask(nodeId, module, status, metadata);
            notifyStatusChange(sessionId);
          }
        });
  }

  /**
   * Finish the workflow.
   *
   * @param sessionId the session identifier
   * @param status the final status
   * @return a Mono that completes when the workflow is finished
   */
  public Mono<Void> finishWorkflow(
      @SessionId final String sessionId, @NotBlank @Size(max = 256) final String status) {
    return Mono.fromRunnable(
        () -> {
          final WorkflowState state = states.get(sessionId);
          if (state != null) {
            state.setStatus(status);
            state.setEndTime(LocalDateTime.now());
            notifyStatusChange(sessionId);
          }
        });
  }

  /**
   * Append a log line to the live output.
   *
   * @param sessionId the session identifier
   * @param line the log line
   * @return a Mono that completes when the log line is appended
   */
  public Mono<Void> appendLog(
      @SessionId final String sessionId, @NotBlank @Size(max = 16_384) final String line) {
    return Mono.fromRunnable(
        () -> {
          final Sinks.Many<String> sink = logSinks.get(sessionId);
          if (sink != null) {
            sink.tryEmitNext(line);
          }
        });
  }

  /**
   * Get the current progress of a workflow.
   *
   * @param sessionId the session identifier
   * @return the workflow progress
   */
  public WorkflowProgress getProgress(@SessionId final String sessionId) {
    final WorkflowState state = states.get(sessionId);
    WorkflowProgress progress = null;
    if (state != null) {
      progress =
          new WorkflowProgress(
              state.sessionId,
              state.status,
              List.copyOf(state.tasks),
              state.startTime,
              state.endTime);
    }
    return progress;
  }

  /**
   * Get the log stream for a session.
   *
   * @param sessionId the session identifier
   * @return the log flux
   */
  public Flux<String> getLogStream(@SessionId final String sessionId) {
    final Sinks.Many<String> sink = logSinks.get(sessionId);
    return sink != null ? sink.asFlux() : Flux.empty();
  }

  /**
   * List all active sessions being tracked.
   *
   * @return list of session IDs
   */
  public List<String> getActiveSessions() {
    return List.copyOf(states.keySet());
  }

  /**
   * Remove tracking data for a session.
   *
   * @param sessionId the session identifier
   */
  public void removeSession(@SessionId final String sessionId) {
    states.remove(sessionId);
    logSinks.remove(sessionId);
    statusSinks.remove(sessionId);
  }

  private void notifyStatusChange(final String sessionId) {
    final Sinks.Many<String> sink = statusSinks.get(sessionId);
    if (sink != null) {
      sink.tryEmitNext("update");
    }
  }

  /**
   * Get the status stream for a session.
   *
   * @param sessionId the session identifier
   * @return the status flux
   */
  public Flux<String> getStatusStream(@SessionId final String sessionId) {
    final Sinks.Many<String> sink = statusSinks.get(sessionId);
    return sink != null ? sink.asFlux() : Flux.empty();
  }

  private static final class WorkflowState {
    private final String sessionId;
    private String status;
    private final List<TaskProgress> tasks;
    private final LocalDateTime startTime;
    private LocalDateTime endTime;

    /* default */ WorkflowState(
        final String sessionId,
        final String status,
        final List<TaskProgress> tasks,
        final LocalDateTime startTime) {
      this.sessionId = sessionId;
      this.status = status;
      this.tasks = new CopyOnWriteArrayList<>(tasks);
      this.startTime = startTime;
    }

    /* default */ void updateTask(
        final String nodeId,
        final String module,
        final String status,
        final Map<String, Object> metadata) {
      int index = -1;
      for (int i = 0; i < tasks.size(); i++) {
        if (tasks.get(i).nodeId().equals(nodeId)) {
          index = i;
          break;
        }
      }

      if (index != -1) {
        final TaskProgress current = tasks.get(index);
        tasks.set(index, createUpdatedTask(current, module, status, metadata));
      }
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
      return ("RUNNING".equals(status) && current == null) ? LocalDateTime.now() : current;
    }

    private LocalDateTime determineEndTime(final LocalDateTime current, final String status) {
      return (("SUCCESS".equals(status) || "FAILURE".equals(status)) && current == null)
          ? LocalDateTime.now()
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
