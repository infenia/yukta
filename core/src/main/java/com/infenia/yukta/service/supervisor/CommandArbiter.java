// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Orders the commands of supervisors that act on the same target, so that two supervisors cannot
 * pause and resume one run in turn.
 *
 * <p>A stop, and every command that cannot conflict, is sent at once. Commands that can conflict
 * (pause and resume, skip and unskip, step mode on and off, a different priority) wait for a short
 * window per target; when it closes, the supervisor with the lowest {@code order} wins and the rest
 * of its side are {@code coalesced}, the opposite side is {@code arbitrated}. A stop seen on the
 * target in the last second cancels a pause or resume. A supervisor that turns one target around
 * more than {@code flapLimit} times in {@code flapPeriod} is blocked from conflicting commands on
 * it for {@code flapCooldown}. A window of zero turns the arbiter off.
 *
 * @since 0.1.0-alpha.4
 */
@SuppressWarnings({
  "PMD.CyclomaticComplexity",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
public class CommandArbiter {

  /** Most windows open at once; past it the oldest closes early. */
  private static final int MAX_WINDOWS = 10_000;

  /** How long a stop cancels a pause or resume that follows. */
  private static final Duration STOP_MEMORY = Duration.ofSeconds(1);

  /** The family and direction of a pause. */
  private static final String PAUSE = "pause";

  /** How long a window stays open. */
  private final Duration window;

  /** How long flips are counted. */
  private final Duration flapPeriod;

  /** Most flips in the period. */
  private final int flapLimit;

  /** How long a flapping supervisor is blocked. */
  private final Duration flapCooldown;

  /** The time. */
  private final Clock clock;

  /** Closes windows. */
  private final Scheduler scheduler;

  /** Guards the maps. */
  private final ReentrantLock lock = new ReentrantLock();

  /** Open windows, oldest first. */
  private final Map<String, Window> windows = new LinkedHashMap<>();

  /** When a stop last passed, by execution. */
  private final Map<String, Instant> stops = new HashMap<>();

  /** Flip counts, by supervisor and target. */
  private final Map<String, Flaps> flaps = new HashMap<>();

  /**
   * Creates the arbiter from the settings.
   *
   * @param settings the {@code yukta.supervisor.arbiter} settings
   */
  public CommandArbiter(final YuktaProperties.Arbiter settings) {
    this(settings, Clock.systemUTC(), Schedulers.parallel());
  }

  /**
   * Creates the arbiter with a clock and a scheduler.
   *
   * @param settings the settings
   * @param clock the time
   * @param scheduler closes the windows
   */
  public CommandArbiter(
      final YuktaProperties.Arbiter settings, final Clock clock, final Scheduler scheduler) {
    this.window = settings.window();
    this.flapPeriod = settings.flapPeriod();
    this.flapLimit = settings.flapLimit();
    this.flapCooldown = settings.flapCooldown();
    this.clock = clock;
    this.scheduler = scheduler;
  }

  /**
   * An arbiter that never delays or changes anything.
   *
   * @return the arbiter
   */
  public static CommandArbiter disabled() {
    return new CommandArbiter(
        new YuktaProperties.Arbiter(
            Duration.ZERO, Duration.ofSeconds(60), 4, Duration.ofMinutes(5)));
  }

  /**
   * Decides what to do with a command of a supervisor.
   *
   * @param supervisorId the supervisor
   * @param order its review order; the lowest wins a conflict
   * @param command the command
   * @return the decision; a conflicting command is decided when its window closes
   */
  public Mono<Decision> submit(
      final String supervisorId, final int order, final ExecutionControlCommand command) {
    if (window.isZero()) {
      return Mono.just(Decision.SEND);
    }
    final Instant now = clock.instant();
    if (command instanceof ExecutionControlCommand.StopWorkflowCommand
        || command instanceof ExecutionControlCommand.StopNodeCommand) {
      noteStop(command.executionId(), now);
      return Mono.just(Decision.SEND);
    }
    final Move move = move(command);
    if (move == null) {
      return Mono.just(Decision.SEND);
    }
    lock.lock();
    try {
      final Instant stopped = stops.get(command.executionId());
      if (move.pausing() && stopped != null && stopped.plus(STOP_MEMORY).isAfter(now)) {
        return Mono.just(new Decision(Verdict.ARBITRATED, "the run was stopped a moment ago"));
      }
      final Flaps own = flaps.get(supervisorId + '|' + move.target());
      if (own != null && own.blockedUntil != null && own.blockedUntil.isAfter(now)) {
        return Mono.just(
            new Decision(Verdict.FLAPPING_BLOCKED, "blocked until " + own.blockedUntil));
      }
      return join(supervisorId, order, move);
    } finally {
      lock.unlock();
    }
  }

  private void noteStop(final String executionId, final Instant now) {
    lock.lock();
    try {
      stops.put(executionId, now);
    } finally {
      lock.unlock();
    }
  }

  /** Adds a command to the window of its target and family, opening it if needed. */
  private Mono<Decision> join(final String supervisorId, final int order, final Move move) {
    final String key = move.target() + '|' + move.family();
    Window open = windows.get(key);
    if (open == null) {
      if (windows.size() >= MAX_WINDOWS) {
        // past the bound the oldest window closes early rather than the memory growing
        close(windows.keySet().iterator().next());
      }
      open = new Window(move);
      windows.put(key, open);
      scheduler.schedule(() -> closeLocked(key), window.toNanos(), TimeUnit.NANOSECONDS);
    }
    final Entry entry = new Entry(supervisorId, order, move.polarity());
    open.entries.add(entry);
    return entry.sink.asMono();
  }

  private void closeLocked(final String key) {
    lock.lock();
    try {
      close(key);
    } finally {
      lock.unlock();
    }
  }

  /** Decides the window of a key; the lock is held. */
  private void close(final String key) {
    final Window closed = windows.remove(key);
    if (closed == null) {
      return;
    }
    // the lowest order wins; on a tie the earlier arrival, since min keeps the first of equals
    final Entry winner =
        closed.entries.stream().min(Comparator.comparingInt((Entry e) -> e.order)).orElseThrow();
    final Instant now = clock.instant();
    boolean sent = false;
    for (final Entry entry : new ArrayList<>(closed.entries)) {
      final boolean winning = entry.polarity.equals(winner.polarity);
      final Decision decision;
      if (winning && !sent) {
        sent = true;
        decision = flapped(entry, closed.move, now);
      } else if (winning) {
        decision =
            new Decision(Verdict.COALESCED, "the same command is sent for another supervisor");
      } else {
        decision =
            new Decision(
                Verdict.ARBITRATED,
                "lost to supervisor " + winner.supervisorId + " (order " + winner.order + ")");
      }
      entry.sink.tryEmitValue(decision);
    }
  }

  /** Counts a turn-around of a supervisor on a target and blocks it once it is too many. */
  private Decision flapped(final Entry entry, final Move move, final Instant now) {
    final Flaps own =
        flaps.computeIfAbsent(entry.supervisorId + '|' + move.target(), _ -> new Flaps());
    final String before = own.last.put(move.family(), entry.polarity);
    if (before != null && !before.equals(entry.polarity)) {
      own.flips.addLast(now);
    }
    while (!own.flips.isEmpty() && own.flips.peekFirst().plus(flapPeriod).isBefore(now)) {
      own.flips.removeFirst();
    }
    if (own.flips.size() > flapLimit) {
      own.blockedUntil = now.plus(flapCooldown);
      own.flips.clear();
      return new Decision(
          Verdict.FLAPPING_BLOCKED, "turned " + move.target() + " around too often; blocked");
    }
    return Decision.SEND;
  }

  /** The place, kind and direction of a command that can conflict, or null for any other. */
  private static @Nullable Move move(final ExecutionControlCommand command) {
    final String run = command.executionId();
    return switch (command) {
      case ExecutionControlCommand.PauseWorkflowCommand _ -> new Move(run, PAUSE, PAUSE, true);
      case ExecutionControlCommand.ResumeWorkflowCommand _ -> new Move(run, PAUSE, "resume", true);
      case ExecutionControlCommand.PauseNodeCommand c ->
          new Move(run + '/' + c.nodeId(), PAUSE, PAUSE, true);
      case ExecutionControlCommand.ResumeNodeCommand c ->
          new Move(run + '/' + c.nodeId(), PAUSE, "resume", true);
      case ExecutionControlCommand.SkipNodeCommand c ->
          new Move(run + '/' + c.nodeId(), "skip", String.valueOf(c.skip()), false);
      case ExecutionControlCommand.EnableStepModeCommand c ->
          new Move(run + '/' + c.nodeId(), "step", "on", false);
      case ExecutionControlCommand.DisableStepModeCommand c ->
          new Move(run + '/' + c.nodeId(), "step", "off", false);
      case ExecutionControlCommand.SetPriorityCommand c ->
          new Move(run, "priority", String.valueOf(c.priority()), false);
      default -> null;
    };
  }

  /**
   * Where a command acts, what kind of change it is, its direction, and whether a stop voids it.
   */
  private record Move(String target, String family, String polarity, boolean pausing) {}

  /** The commands waiting for one target and family. */
  private static final class Window {
    private final Move move;
    private final List<Entry> entries = new ArrayList<>();

    /* default */ Window(final Move move) {
      this.move = move;
    }
  }

  /** One supervisor's command in a window. */
  private static final class Entry {
    private final String supervisorId;
    private final int order;
    private final String polarity;
    private final Sinks.One<Decision> sink = Sinks.one();

    /* default */ Entry(final String supervisorId, final int order, final String polarity) {
      this.supervisorId = supervisorId;
      this.order = order;
      this.polarity = polarity;
    }
  }

  /** What one supervisor did to one target lately. */
  private static final class Flaps {
    private final Map<String, String> last = new HashMap<>();
    private final Deque<Instant> flips = new ArrayDeque<>();
    private @Nullable Instant blockedUntil;
  }

  /** What to do with a command. */
  public enum Verdict {
    /** Send it. */
    SEND,
    /** Another supervisor won a conflict over it. */
    ARBITRATED,
    /** The same command from another supervisor is sent instead. */
    COALESCED,
    /** The supervisor turned the target around too often and is blocked for a while. */
    FLAPPING_BLOCKED
  }

  /**
   * The verdict on a command and why.
   *
   * @param verdict what to do
   * @param reason why, for the record
   */
  public record Decision(Verdict verdict, String reason) {

    /** Send it, no reason needed. */
    public static final Decision SEND = new Decision(Verdict.SEND, "");
  }
}
