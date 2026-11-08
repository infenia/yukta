// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Stops one supervisor from flapping against itself: pausing and resuming, skipping and unskipping,
 * stepping on and off, or moving the priority of one run or node back and forth. More than {@code
 * limit} turn-arounds of one target in {@code period} block that supervisor's turn-arounds of the
 * target for {@code cooldown}. With one owner per unit, supervisors no longer fight each other, so
 * this is all that is left of the old arbiter.
 */
public final class TurnAroundGuard {

  private final Duration period;
  private final int limit;
  private final Duration cooldown;
  private final Clock clock;
  private final Map<String, Record> records = new HashMap<>();

  /**
   * Creates the guard from the settings.
   *
   * @param settings the {@code yukta.supervisor.turn-around} settings
   */
  public TurnAroundGuard(final YuktaProperties.TurnAround settings) {
    this(settings, Clock.systemUTC());
  }

  /**
   * Creates the guard with a clock.
   *
   * @param settings the settings
   * @param clock the clock
   */
  public TurnAroundGuard(final YuktaProperties.TurnAround settings, final Clock clock) {
    this.period = settings.period();
    this.limit = settings.limit();
    this.cooldown = settings.cooldown();
    this.clock = clock;
  }

  /**
   * Notes a command and says whether it is blocked.
   *
   * @param supervisorId the sender
   * @param command the command
   * @return the reason it is blocked, empty when it may go
   */
  public synchronized Optional<String> check(
      final String supervisorId, final ExecutionControlCommand command) {
    final Move move = move(command);
    if (move == null) {
      return Optional.empty();
    }
    final Instant now = clock.instant();
    records.values().removeIf(r -> r.touched.plus(period).plus(cooldown).isBefore(now));
    final Record own = records.computeIfAbsent(supervisorId + '|' + move.target, _ -> new Record());
    own.touched = now;
    if (own.blockedUntil.isAfter(now)) {
      return Optional.of("turned " + move.target + " around too often; blocked");
    }
    final String before = own.last.put(move.family, move.direction);
    if (before != null && !before.equals(move.direction)) {
      own.turns.addLast(now);
    }
    while (!own.turns.isEmpty() && own.turns.peekFirst().plus(period).isBefore(now)) {
      own.turns.removeFirst();
    }
    if (own.turns.size() > limit) {
      own.blockedUntil = now.plus(cooldown);
      own.turns.clear();
      return Optional.of("turned " + move.target + " around too often; blocked");
    }
    return Optional.empty();
  }

  private static @Nullable Move move(final ExecutionControlCommand command) {
    final String run = command.executionId();
    return switch (command) {
      case ExecutionControlCommand.PauseWorkflowCommand _ -> new Move(run, "pause", "pause");
      case ExecutionControlCommand.ResumeWorkflowCommand _ -> new Move(run, "pause", "resume");
      case ExecutionControlCommand.PauseNodeCommand c ->
          new Move(run + '/' + c.nodeId(), "pause", "pause");
      case ExecutionControlCommand.ResumeNodeCommand c ->
          new Move(run + '/' + c.nodeId(), "pause", "resume");
      case ExecutionControlCommand.SkipNodeCommand c ->
          new Move(run + '/' + c.nodeId(), "skip", String.valueOf(c.skip()));
      case ExecutionControlCommand.EnableStepModeCommand c ->
          new Move(run + '/' + c.nodeId(), "step", "on");
      case ExecutionControlCommand.DisableStepModeCommand c ->
          new Move(run + '/' + c.nodeId(), "step", "off");
      case ExecutionControlCommand.SetPriorityCommand c ->
          new Move(run, "priority", String.valueOf(c.priority()));
      default -> null;
    };
  }

  /** Where a command acts, what kind of change it is and its direction. */
  private record Move(String target, String family, String direction) {}

  /** What one supervisor did to one target lately. */
  private static final class Record {
    private final Map<String, String> last = new HashMap<>();
    private final Deque<Instant> turns = new ArrayDeque<>();
    private Instant blockedUntil = Instant.MIN;
    private Instant touched = Instant.MIN;
  }
}
