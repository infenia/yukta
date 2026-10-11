// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import org.jspecify.annotations.Nullable;

/**
 * A control command waiting for {@link ExecutionSupervisor#review} approval.
 *
 * @param command the command
 * @param actor who sent it: a supervisor id, or {@code null} when it came through the API
 */
public record ControlRequest(ExecutionControlCommand command, @Nullable String actor) {}
