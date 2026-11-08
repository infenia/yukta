// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The executions each supervisor paused, so "resume held" resumes only those and never a run that a
 * person paused. In memory: it starts empty after a restart.
 */
public final class HoldLedger {

  /** Most executions remembered per supervisor. */
  public static final int MAX_PER_SUPERVISOR = 1000;

  private final Map<String, SequencedSet<String>> bySupervisor = new HashMap<>(); // NOPMD - locked
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Notes a command a supervisor applied: a pause adds the run, a resume or a stop removes it.
   *
   * @param supervisorId the supervisor
   * @param command the command that was applied
   */
  public void applied(final String supervisorId, final ExecutionControlCommand command) {
    switch (command) {
      case ExecutionControlCommand.PauseWorkflowCommand pause -> hold(supervisorId, pause);
      case ExecutionControlCommand.ResumeWorkflowCommand resume ->
          release(supervisorId, resume.executionId());
      case ExecutionControlCommand.StopWorkflowCommand stop ->
          release(supervisorId, stop.executionId());
      default -> {
        // other commands do not change what is held
      }
    }
  }

  private void hold(final String supervisorId, final ExecutionControlCommand pause) {
    lock.lock();
    try {
      final SequencedSet<String> mine =
          bySupervisor.computeIfAbsent(supervisorId, id -> new LinkedHashSet<>());
      mine.add(pause.executionId());
      // ponytail: no end-of-run hook; the oldest entry (most likely a finished run) goes first
      if (mine.size() > MAX_PER_SUPERVISOR) {
        mine.removeFirst();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The executions a supervisor holds, oldest first.
   *
   * @param supervisorId the supervisor
   * @return their ids
   */
  public List<String> held(final String supervisorId) {
    lock.lock();
    try {
      return List.copyOf(bySupervisor.getOrDefault(supervisorId, new LinkedHashSet<>()));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Forgets one execution of a supervisor.
   *
   * @param supervisorId the supervisor
   * @param executionId the execution
   */
  public void release(final String supervisorId, final String executionId) {
    lock.lock();
    try {
      final SequencedSet<String> mine = bySupervisor.get(supervisorId);
      if (mine != null) {
        mine.remove(executionId);
      }
    } finally {
      lock.unlock();
    }
  }
}
