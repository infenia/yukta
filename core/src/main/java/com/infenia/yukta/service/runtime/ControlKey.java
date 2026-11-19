// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import org.jspecify.annotations.Nullable;

/**
 * Key of the control bus: one node of one run of one workflow.
 *
 * <p>Two runs of the same workflow have the same node ids, so without the execution id their plugin
 * registrations would overwrite each other. A key made without an execution id is for a plugin that
 * does not belong to a run.
 *
 * @param workflowId the workflow
 * @param executionId the run, or null if the key does not belong to a run
 * @param nodeId the node
 */
public record ControlKey(String workflowId, @Nullable String executionId, String nodeId) {

  /**
   * Builds the key of a node.
   *
   * @param workflowId the workflow
   * @param executionId the run, or null or empty if the signal does not belong to a run
   * @param nodeId the node
   * @return the key
   */
  public static ControlKey forNode(
      final String workflowId, final @Nullable String executionId, final String nodeId) {
    return new ControlKey(
        workflowId, executionId == null || executionId.isEmpty() ? null : executionId, nodeId);
  }
}
