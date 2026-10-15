// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import java.time.Instant;

/**
 * A node that waits for a signal (an approval or decision) now.
 *
 * @param executionId the run that waits
 * @param workflowId the workflow of that run
 * @param nodeId the node that waits
 * @param name the signal it waits for
 * @param since when the wait began
 */
public record PendingApproval(
    String executionId, String workflowId, String nodeId, String name, Instant since) {}
