// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One control command and what became of it: the record an {@link AuditSink} receives.
 *
 * @param applied whether the command was applied (false: refused, failed or cancelled)
 * @param executionId the run the command was for ({@code scheduler} for limit changes)
 * @param command the command name, such as {@code PauseWorkflowCommand}
 * @param actor who sent it ({@code key:<name>} or a supervisor id), or null when unknown
 * @param reason why it was not applied, or null
 * @param timestamp when it was recorded
 * @param channel how the command arrived ({@code OPERATOR}, {@code ENGINE}, {@code SYSTEM}), or
 *     null when not recorded
 */
public record AuditRecord(
    boolean applied,
    String executionId,
    String command,
    @Nullable String actor,
    @Nullable String reason,
    Instant timestamp,
    @Nullable String channel) {

  /**
   * A record with no channel recorded.
   *
   * @param applied whether the command was applied
   * @param executionId the run
   * @param command the command name
   * @param actor who sent it, or null
   * @param reason why it was not applied, or null
   * @param timestamp when it was recorded
   */
  public AuditRecord(
      final boolean applied,
      final String executionId,
      final String command,
      @Nullable final String actor,
      @Nullable final String reason,
      final Instant timestamp) {
    this(applied, executionId, command, actor, reason, timestamp, null);
  }
}
