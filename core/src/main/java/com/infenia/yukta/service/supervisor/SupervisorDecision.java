// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What became of one command a supervisor emitted.
 *
 * @param time when it was decided
 * @param supervisorId the supervisor, or the binding id of an attached one
 * @param outcome {@code applied}, {@code rejected}, {@code dry-run} or {@code rate-limited}
 * @param command the command name, such as {@code PauseWorkflowCommand}
 * @param executionId the run it addressed
 * @param reason why it was not applied, or null
 */
public record SupervisorDecision(
    Instant time,
    String supervisorId,
    String outcome,
    String command,
    String executionId,
    @Nullable String reason) {}
