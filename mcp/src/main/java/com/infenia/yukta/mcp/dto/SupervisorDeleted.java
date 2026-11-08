// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A supervisor that was deleted.
 *
 * @param id the supervisor id
 * @param deleted always true; a refusal is an error instead
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorDeleted(String id, boolean deleted) {}
