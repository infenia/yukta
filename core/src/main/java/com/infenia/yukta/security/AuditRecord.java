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
 */
public record AuditRecord(
    boolean applied,
    String executionId,
    String command,
    @Nullable String actor,
    @Nullable String reason,
    Instant timestamp) {}
