// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Keys of the control bus: one node of one run of one workflow.
 *
 * <p>A key is {@code workflowId NUL executionId NUL nodeId}. Two runs of the same workflow have the
 * same node ids, so without the execution id their plugin registrations would overwrite each other.
 * A key made without an execution id ({@code workflowId NUL nodeId}) is for a plugin that does not
 * belong to a run.
 */
@UtilityClass
public class ControlKey {

  /** Separates the parts of a key; no id can contain it. */
  private static final char SEPARATOR = '\0';

  /**
   * Builds the key of a node.
   *
   * @param workflowId the workflow
   * @param executionId the run, or null or empty if the signal does not belong to a run
   * @param nodeId the node
   * @return the key
   */
  public static String forNode(
      final String workflowId, final @Nullable String executionId, final String nodeId) {
    return executionId == null || executionId.isEmpty()
        ? workflowId + SEPARATOR + nodeId
        : workflowId + SEPARATOR + executionId + SEPARATOR + nodeId;
  }
}
