// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import org.jspecify.annotations.Nullable;

/**
 * A control command waiting for {@link ExecutionSupervisor#review} approval.
 *
 * @param command the command
 * @param actor who sent it: a supervisor id, {@code key:<name>} for a named API key, or {@code
 *     null} when it came through the API with the single key or no key
 * @param sessionId the session of the run the command addresses, or {@code null} when the command
 *     belongs to no run or the run is not known
 * @param workflowId the workflow of that run, or {@code null} likewise
 */
public record ControlRequest(
    ExecutionControlCommand command,
    @Nullable String actor,
    @Nullable String sessionId,
    @Nullable String workflowId) {}
