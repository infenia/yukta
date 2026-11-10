// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * What the supervisor checks on every command an engine returns, whatever the engine is: the
 * supervisor is enabled, the command is one it may send, the target is a unit it owns, and a
 * command with no triggering signal (a timer or the engine's own initiative) has a reason, is in
 * the {@code initiative} list and is within the per-minute limit. Dry run records and applies
 * nothing. The target comes from the command, never from what the engine says about it.
 */
public final class DirectiveGate {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final Clock clock;
  private final Map<String, Deque<Instant>> initiated = new ConcurrentHashMap<>();
  private final ReentrantLock lock = new ReentrantLock();

  /** Why a command is not applied. */
  public enum Rejection {
    /** The supervisor is disabled. */
    DISABLED,
    /** The command is not in {@code allowedCommands}. */
    NOT_ALLOWED,
    /** The target is not a unit the supervisor owns. */
    NOT_OWNED,
    /** A tick or an initiative came with no reason. */
    REASON_REQUIRED,
    /** A tick or an initiative with a command not in {@code initiative}. */
    INITIATIVE_NOT_ALLOWED,
    /** More commands without a triggering signal than {@code maxInitiativePerMinute}. */
    INITIATIVE_RATE_LIMITED
  }

  /**
   * What a command acts on, looked up by the server from the command itself.
   *
   * @param sessionId the session, or null when the lookup failed
   * @param workflowId the workflow, or null
   * @param nodeId the node, or null for a run-level command
   */
  public record Target(
      @Nullable String sessionId, @Nullable String workflowId, @Nullable String nodeId) {}

  /**
   * The verdict on a command.
   *
   * @param accepted whether it goes on to be applied
   * @param recordOnly whether it is only recorded (dry run)
   * @param rejection why not, or {@code null} when accepted
   */
  public record Decision(boolean accepted, boolean recordOnly, @Nullable Rejection rejection) {

    /**
     * The outcome a rejected command is reported with.
     *
     * @return {@code NOT_OWNED} for a target outside the supervisor, else {@code null}: the other
     *     rejections have a reason but are not about the target
     */
    public @Nullable Outcome outcome() {
      return rejection == Rejection.NOT_OWNED ? Outcome.NOT_OWNED : null;
    }
  }

  /** Creates the gate on the system clock. */
  public DirectiveGate() {
    this(Clock.systemUTC());
  }

  /**
   * Creates the gate.
   *
   * @param clock the clock for the per-minute limit
   */
  public DirectiveGate(final Clock clock) {
    this.clock = clock;
  }

  /**
   * Checks one command.
   *
   * @param supervisorId the supervisor
   * @param directive the command and its cause
   * @param target where the command lands: its session, workflow and node, each {@code null} where
   *     it does not reach that far (the server, a run level)
   * @param capabilities what the supervisor may send
   * @param scope what the supervisor owns
   * @param enabled whether the supervisor is enabled
   * @param dryRun whether its commands are only recorded
   * @return the decision
   */
  public Decision check(
      final String supervisorId,
      final Directive.Command directive,
      final DirectiveGate.Target target,
      final SupervisorCapabilities capabilities,
      final SupervisorScope scope,
      final boolean enabled,
      final boolean dryRun) {
    Rejection rejection = screen(directive, target, capabilities, scope, enabled);
    if (rejection == null && directive.cause().unprompted()) {
      rejection = initiativeRejection(supervisorId, directive, capabilities, dryRun);
    }
    return rejection == null
        ? new Decision(!dryRun, dryRun, null)
        : new Decision(false, false, rejection);
  }

  private static @Nullable Rejection screen(
      final Directive.Command directive,
      final DirectiveGate.Target target,
      final SupervisorCapabilities capabilities,
      final SupervisorScope scope,
      final boolean enabled) {
    if (!enabled) {
      return Rejection.DISABLED;
    }
    if (!capabilities.allows(directive.command())) {
      return Rejection.NOT_ALLOWED;
    }
    // an attached Global scope is refused by the host before this; a bean supervisor's Global
    // scope keeps its legacy reach
    if (!(scope instanceof SupervisorScope.Global)
        && !scope.owns(
            directive.command(), target.sessionId(), target.workflowId(), target.nodeId())) {
      return Rejection.NOT_OWNED;
    }
    return null;
  }

  private @Nullable Rejection initiativeRejection(
      final String supervisorId,
      final Directive.Command directive,
      final SupervisorCapabilities capabilities,
      final boolean dryRun) {
    final String reason = directive.reason();
    if (reason == null || reason.isBlank()) {
      return Rejection.REASON_REQUIRED;
    }
    if (!capabilities.mayInitiate(directive.command())) {
      return Rejection.INITIATIVE_NOT_ALLOWED;
    }
    lock.lock();
    try {
      final Instant now = clock.instant();
      final Deque<Instant> recent =
          initiated.computeIfAbsent(supervisorId, _ -> new ArrayDeque<>());
      while (!recent.isEmpty() && recent.peekFirst().plus(WINDOW).isBefore(now)) {
        recent.removeFirst();
      }
      if (recent.size() >= capabilities.maxInitiativePerMinute()) {
        return Rejection.INITIATIVE_RATE_LIMITED;
      }
      if (!dryRun) {
        recent.addLast(now);
      }
      return null;
    } finally {
      lock.unlock();
    }
  }
}
