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
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The executions each supervisor paused, so "resume held" resumes only those and never a run that a
 * person paused. Every change is passed, in order and under the lock, to a listener that keeps the
 * ledger in a store, and {@link #load} brings it back after a restart.
 */
public final class HoldLedger {

  /** Most executions remembered per supervisor. */
  public static final int MAX_PER_SUPERVISOR = 1000;

  private final Map<String, SequencedSet<String>> bySupervisor = new HashMap<>(); // NOPMD - locked
  private final ReentrantLock lock = new ReentrantLock();
  private volatile Consumer<Change> listener = change -> {};

  /**
   * A change to the ledger.
   *
   * @param holder the supervisor or attachment id; {@code null} for every holder
   * @param executionId the run
   * @param held true when the run was added, false when it was released
   */
  public record Change(@Nullable String holder, String executionId, boolean held) {}

  /**
   * Sets who hears every change. It is called under the ledger's lock, so it must not block.
   *
   * @param changes the listener
   */
  public void onChange(final Consumer<Change> changes) {
    this.listener = changes;
  }

  /**
   * Puts back a hold that was kept before a restart, without passing it to the listener.
   *
   * @param holder the supervisor or attachment id
   * @param executionId the run
   */
  public void load(final String holder, final String executionId) {
    lock.lock();
    try {
      add(holder, executionId);
    } finally {
      lock.unlock();
    }
  }

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
      if (add(supervisorId, pause.executionId()) instanceof String dropped) {
        listener.accept(new Change(supervisorId, dropped, false));
      }
      listener.accept(new Change(supervisorId, pause.executionId(), true));
    } finally {
      lock.unlock();
    }
  }

  /** Adds a run; returns the oldest one dropped over the cap, if any. Call under the lock. */
  private @Nullable String add(final String holder, final String executionId) {
    final SequencedSet<String> mine =
        bySupervisor.computeIfAbsent(holder, id -> new LinkedHashSet<>());
    mine.add(executionId);
    // the end of a run releases it (releaseEverywhere); the cap only bounds a missed end
    return mine.size() > MAX_PER_SUPERVISOR ? mine.removeFirst() : null;
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
   * Forgets a run for every supervisor: someone resumed or stopped it, or it ended, so no
   * supervisor's pause holds it any more and {@code resume held} must not touch it.
   *
   * @param executionId the run
   */
  public void releaseEverywhere(final String executionId) {
    lock.lock();
    try {
      boolean removed = false;
      for (final SequencedSet<String> mine : bySupervisor.values()) {
        removed |= mine.remove(executionId);
      }
      // most runs that end were never held: tell the listener only when something changed
      if (removed) {
        listener.accept(new Change(null, executionId, false));
      }
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
      if (mine != null && mine.remove(executionId)) {
        listener.accept(new Change(supervisorId, executionId, false));
      }
    } finally {
      lock.unlock();
    }
  }
}
