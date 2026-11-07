// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * The answer to saving a supervisor. Settings are not echoed, because they may hold secrets.
 *
 * @param id the supervisor id
 * @param type the supervisor type
 * @param description what it is for, or null
 * @param dryRun whether its commands are only recorded
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorSaved(
    String id, String type, @Nullable String description, boolean dryRun) {}
