// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A supervisor attached to a scope.
 *
 * @param id the attachment id
 * @param supervisorId the supervisor it runs
 * @param scope {@code global}, {@code session:<id>}, {@code workflow:<id>}, {@code
 *     workflow:<sessionId>/<workflowId>} or a section {@code workflow:<sessionId>/<workflowId>//
 *     <selector>,<selector>}
 * @param enabled whether it runs
 * @param source {@code MANUAL}, {@code DEFINITION} or {@code SYSTEM}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorAttachmentInfo(
    String id, String supervisorId, String scope, boolean enabled, String source) {}
