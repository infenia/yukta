// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import org.jspecify.annotations.Nullable;

/**
 * What a control command acts on, as supervisor scopes are matched against it.
 *
 * @param sessionId the session of the run, or {@code null} when the command belongs to no run or
 *     the run is not known
 * @param workflowId the workflow of the run, or {@code null} likewise
 * @param nodeId the node, or {@code null} when the command acts on the whole run or on no run
 */
public record CommandTarget(
    @Nullable String sessionId, @Nullable String workflowId, @Nullable String nodeId) {}
