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
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the supervisor checks on every command an engine returns, whatever the engine is: the
 * supervisor is enabled, the command is one it may send, the target is a unit it owns, and a
 * command with no triggering signal (a timer or the engine's own initiative) has a reason, is in
 * the {@code initiative} list and is within the per-minute limit. Dry run records and applies
 * nothing. The target comes from the command, never from what the engine says about it.
 */
public final class DirectiveGate {

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

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final Clock clock;
  private final Map<String, Deque<Instant>> initiated = new HashMap<>();

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
   * @param sessionId the session of the target, or {@code null} for the server
   * @param workflowId the workflow of the target, or {@code null} for the server
   * @param nodeId the node of the target, or {@code null} for a run level or the server
   * @param capabilities what the supervisor may send
   * @param scope what the supervisor owns
   * @param enabled whether the supervisor is enabled
   * @param dryRun whether its commands are only recorded
   * @return the decision
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public synchronized Decision check(
      final String supervisorId,
      final Directive.Command directive,
      final @Nullable String sessionId,
      final @Nullable String workflowId,
      final @Nullable String nodeId,
      final SupervisorCapabilities capabilities,
      final SupervisorScope scope,
      final boolean enabled,
      final boolean dryRun) {
    Rejection rejection = null;
    if (!enabled) {
      rejection = Rejection.DISABLED;
    } else if (!capabilities.allows(directive.command())) {
      rejection = Rejection.NOT_ALLOWED;
    } else if (!scope.matches(sessionId, workflowId, nodeId)) {
      rejection = Rejection.NOT_OWNED;
    } else if (directive.cause().unprompted()) {
      rejection = initiativeRejection(supervisorId, directive, capabilities, dryRun);
    }
    return rejection == null
        ? new Decision(!dryRun, dryRun, null)
        : new Decision(false, false, rejection);
  }

  private @Nullable Rejection initiativeRejection(
      final String supervisorId,
      final Directive.Command directive,
      final SupervisorCapabilities capabilities,
      final boolean dryRun) {
    if (directive.reason() == null || directive.reason().isBlank()) {
      return Rejection.REASON_REQUIRED;
    }
    if (!capabilities.mayInitiate(directive.command())) {
      return Rejection.INITIATIVE_NOT_ALLOWED;
    }
    final Instant now = clock.instant();
    final Deque<Instant> recent = initiated.computeIfAbsent(supervisorId, _ -> new ArrayDeque<>());
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
  }
}
