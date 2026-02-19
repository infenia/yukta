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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Service for tracking the progress of workflows and tasks. */
@Service
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
   * @param taskNames the list of task names in the workflow
   */
  public void startWorkflow(final String sessionId, final List<String> taskNames) {
    final List<TaskProgress> initialTasks =
        taskNames.stream().map(name -> new TaskProgress(name, "", "PENDING", null, null)).toList();
    states.put(
        sessionId,
        new WorkflowState(
            sessionId, "RUNNING", new ArrayList<>(initialTasks), LocalDateTime.now()));

    final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
    logSinks.put(sessionId, sink);

    final Sinks.Many<String> statusSink = Sinks.many().multicast().directBestEffort();
    statusSinks.put(sessionId, statusSink);
  }

  /**
   * Update the status of a specific task.
   *
   * @param sessionId the session identifier
   * @param taskName the name of the task
   * @param module the module name
   * @param status the new status
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public void updateTaskStatus(
      final String sessionId, final String taskName, final String module, final String status) {
    final WorkflowState state = states.get(sessionId);
    if (state != null) {
      state.updateTask(taskName, module, status);
      notifyStatusChange(sessionId);
    }
  }

  /**
   * Finish the workflow.
   *
   * @param sessionId the session identifier
   * @param status the final status
   */
  public void finishWorkflow(final String sessionId, final String status) {
    final WorkflowState state = states.get(sessionId);
    if (state != null) {
      state.setStatus(status);
      state.setEndTime(LocalDateTime.now());
      notifyStatusChange(sessionId);
    }
  }

  /**
   * Append a log line to the live output.
   *
   * @param sessionId the session identifier
   * @param line the log line
   */
  public void appendLog(final String sessionId, final String line) {
    final Sinks.Many<String> sink = logSinks.get(sessionId);
    if (sink != null) {
      sink.tryEmitNext(line);
    }
  }

  /**
   * Get the current progress of a workflow.
   *
   * @param sessionId the session identifier
   * @return the workflow progress
   */
  public WorkflowProgress getProgress(final String sessionId) {
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
  public Flux<String> getLogStream(final String sessionId) {
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
  public void removeSession(final String sessionId) {
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
  public Flux<String> getStatusStream(final String sessionId) {
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

    /* default */ void updateTask(final String taskName, final String module, final String status) {
      for (int i = 0; i < tasks.size(); i++) {
        final TaskProgress taskProgress = tasks.get(i);
        if (taskProgress.taskName().equals(taskName)) {
          LocalDateTime taskStartTime = taskProgress.startTime();
          LocalDateTime taskEndTime = taskProgress.endTime();
          if ("RUNNING".equals(status) && taskStartTime == null) {
            taskStartTime = LocalDateTime.now();
          } else if (("SUCCESS".equals(status) || "FAILURE".equals(status))
              && taskEndTime == null) {
            taskEndTime = LocalDateTime.now();
          }
          tasks.set(i, new TaskProgress(taskName, module, status, taskStartTime, taskEndTime));
          break;
        }
      }
    }

    /* default */ void setStatus(final String status) {
      this.status = status;
    }

    /* default */ void setEndTime(final LocalDateTime endTime) {
      this.endTime = endTime;
    }
  }
}
