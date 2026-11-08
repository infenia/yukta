// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The executions each supervisor paused, so "resume held" resumes only those and never a run that a
 * person paused. In memory: it starts empty after a restart.
 */
public final class HoldLedger {

  /** Most executions remembered per supervisor. */
  public static final int MAX_PER_SUPERVISOR = 1000;

  private final Map<String, Set<String>> held = new ConcurrentHashMap<>();

  /**
   * Notes a command a supervisor applied: a pause adds the run, a resume or a stop removes it.
   *
   * @param supervisorId the supervisor
   * @param command the command that was applied
   */
  public void applied(final String supervisorId, final ExecutionControlCommand command) {
    switch (command) {
      case ExecutionControlCommand.PauseWorkflowCommand pause -> {
        final Set<String> mine =
            held.computeIfAbsent(
                supervisorId, id -> Collections.synchronizedSet(new LinkedHashSet<>()));
        synchronized (mine) {
          mine.add(pause.executionId());
          // ponytail: no end-of-run hook; the oldest entry (most likely a finished run) goes first
          if (mine.size() > MAX_PER_SUPERVISOR) {
            mine.remove(mine.iterator().next());
          }
        }
      }
      case ExecutionControlCommand.ResumeWorkflowCommand resume ->
          release(supervisorId, resume.executionId());
      case ExecutionControlCommand.StopWorkflowCommand stop ->
          release(supervisorId, stop.executionId());
      default -> {
        // other commands do not change what is held
      }
    }
  }

  /**
   * The executions a supervisor paused and has not resumed.
   *
   * @param supervisorId the supervisor
   * @return their ids, oldest first
   */
  public List<String> held(final String supervisorId) {
    final Set<String> mine = held.get(supervisorId);
    if (mine == null) {
      return List.of();
    }
    synchronized (mine) {
      return List.copyOf(mine);
    }
  }

  /**
   * Forgets one held execution.
   *
   * @param supervisorId the supervisor
   * @param executionId the run
   */
  public void release(final String supervisorId, final String executionId) {
    final Set<String> mine = held.get(supervisorId);
    if (mine != null) {
      mine.remove(executionId);
    }
  }
}
