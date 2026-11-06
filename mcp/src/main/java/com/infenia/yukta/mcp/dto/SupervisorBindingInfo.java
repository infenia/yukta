// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A profile attached to a scope.
 *
 * @param id the binding id
 * @param profileId the profile it runs
 * @param scope {@code global}, {@code session:<id>}, {@code workflow:<id>}, {@code
 *     workflow:<sessionId>/<workflowId>} or a section {@code workflow:<sessionId>/<workflowId>//
 *     <selector>,<selector>}
 * @param enabled whether it runs
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorBindingInfo(String id, String profileId, String scope, boolean enabled) {}
