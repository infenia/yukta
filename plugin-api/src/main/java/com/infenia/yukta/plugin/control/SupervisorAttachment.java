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
    this(id, supervisorId, scope, enabled, SupervisorSource.MANUAL, null, null, false);
  }

  /**
   * An attachment that is not locked.
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
    this(id, supervisorId, scope, enabled, source, originSessionId, originWorkflowId, false);
  }

  /**
   * The same attachment, locked or not.
   *
   * @param lock whether it is locked
   * @return the attachment
   */
  public SupervisorAttachment withLocked(final boolean lock) {
    return new SupervisorAttachment(
        id, supervisorId, scope, enabled, source, originSessionId, originWorkflowId, lock);
  }
}
