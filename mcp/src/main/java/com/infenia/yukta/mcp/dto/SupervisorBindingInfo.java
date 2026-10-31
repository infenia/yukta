// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * A profile attached to a scope.
 *
 * @param id the binding id
 * @param profileId the profile it runs
 * @param scope {@code global}, {@code session:<id>}, {@code workflow:<id>} or {@code
 *     workflow:<sessionId>/<workflowId>}
 * @param enabled whether it runs
 */
public record SupervisorBindingInfo(String id, String profileId, String scope, boolean enabled) {}
