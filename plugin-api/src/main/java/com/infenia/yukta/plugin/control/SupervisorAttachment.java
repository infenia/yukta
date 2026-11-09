// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Attaches a {@link Supervisor} to a scope: the supervisor watches and may veto only the runs
 * within the scope.
 *
 * @param id the attachment id, unique; it is the supervisor's id in audit entries
 * @param supervisorId the supervisor to run
 * @param scope the runs to supervise
 * @param enabled whether the supervisor runs; a disabled attachment is kept but not started
 * @param source who made it
 * @param originSessionId for {@link SupervisorSource#DEFINITION}, the session that declared it
 * @param originWorkflowId for {@link SupervisorSource#DEFINITION}, the workflow that declared it,
 *     if any
 * @param conflict true when start-up found it owning a unit that another attachment owns and
 *     disabled it; the administrator resolves it
 * @param locked whether only a lock holder may change or remove it ({@code
 *     yukta.supervisor.lock-holders}), so an administrator key alone cannot take away the
 *     supervisor that protects a workflow
 */
public record SupervisorAttachment(
    String id,
    String supervisorId,
    SupervisorScope scope,
    boolean enabled,
    SupervisorSource source,
    @Nullable String originSessionId,
    @Nullable String originWorkflowId,
    boolean conflict,
    boolean locked) {

  /** Rejects missing parts. */
  public SupervisorAttachment {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(supervisorId, "supervisorId");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(source, "source");
  }

  /**
   * A manual attachment, which is how attachments were made before they had a source.
   *
   * @param id the attachment id
   * @param supervisorId the supervisor to run
   * @param scope the runs to supervise
   * @param enabled whether the supervisor runs
   */
  public SupervisorAttachment(
      final String id,
      final String supervisorId,
      final SupervisorScope scope,
      final boolean enabled) {
    this(id, supervisorId, scope, enabled, SupervisorSource.MANUAL, null, null, false, false);
  }

  /**
   * An attachment with no conflict.
   *
   * @param id the attachment id
   * @param supervisorId the supervisor to run
   * @param scope the runs to supervise
   * @param enabled whether the supervisor runs
   * @param source who made it
   * @param originSessionId the session that declared it, or {@code null}
   * @param originWorkflowId the workflow that declared it, or {@code null}
   */
  public SupervisorAttachment(
      final String id,
      final String supervisorId,
      final SupervisorScope scope,
      final boolean enabled,
      final SupervisorSource source,
      @Nullable final String originSessionId,
      @Nullable final String originWorkflowId) {
    this(id, supervisorId, scope, enabled, source, originSessionId, originWorkflowId, false, false);
  }

  /**
   * An attachment as stored before {@code locked} existed: not locked.
   *
   * @param id the attachment id
   * @param supervisorId the supervisor it starts
   * @param scope what it owns
   * @param enabled whether it runs
   * @param source who made it
   * @param originSessionId the session whose definition declares it, or {@code null}
   * @param originWorkflowId the workflow whose definition declares it, or {@code null}
   * @param conflict whether it was disabled because it overlapped another at start-up
   */
  public SupervisorAttachment(
      final String id,
      final String supervisorId,
      final SupervisorScope scope,
      final boolean enabled,
      final SupervisorSource source,
      @Nullable final String originSessionId,
      @Nullable final String originWorkflowId,
      final boolean conflict) {
    this(
        id,
        supervisorId,
        scope,
        enabled,
        source,
        originSessionId,
        originWorkflowId,
        conflict,
        false);
  }

  /**
   * The same attachment, disabled because it overlaps another one.
   *
   * @return the attachment with {@code enabled} false and {@code conflict} true
   */
  public SupervisorAttachment asConflict() {
    return new SupervisorAttachment(
        id, supervisorId, scope, false, source, originSessionId, originWorkflowId, true, locked);
  }

  /**
   * The same attachment, locked or not.
   *
   * @param lock whether it is locked
   * @return the attachment
   */
  public SupervisorAttachment withLocked(final boolean lock) {
    return new SupervisorAttachment(
        id,
        supervisorId,
        scope,
        enabled,
        source,
        originSessionId,
        originWorkflowId,
        conflict,
        lock);
  }
}
