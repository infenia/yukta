// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import java.time.Instant;

/**
 * The last heartbeat of one node of one run.
 *
 * @param sessionId the session that owns the run, or empty if the run is not live (the control bus
 *     itself does not know sessions; the gateway fills it in)
 * @param workflowId the workflow of the node
 * @param executionId the run, or empty if the heartbeat belongs to no run
 * @param nodeId the node
 * @param timestamp when the heartbeat was sent
 */
public record NodeHeartbeat(
    String sessionId, String workflowId, String executionId, String nodeId, Instant timestamp) {}
