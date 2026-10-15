// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Keys of the control bus: one node of one run of one workflow.
 *
 * <p>A key is {@code workflowId NUL executionId NUL nodeId}. Two runs of the same workflow have the
 * same node ids, so without the execution id their heartbeats, statistics and plugin registrations
 * would overwrite each other. A key made without an execution id ({@code workflowId NUL nodeId}) is
 * for a signal that does not belong to a run.
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

  /**
   * The workflow of a key.
   *
   * @param key the key
   * @return the workflow id
   */
  public static String workflowId(final String key) {
    return key.substring(0, key.indexOf(SEPARATOR));
  }

  /**
   * The run of a key.
   *
   * @param key the key
   * @return the execution id, or empty if the key has none
   */
  public static String executionId(final String key) {
    final int first = key.indexOf(SEPARATOR);
    final int last = key.lastIndexOf(SEPARATOR);
    return first == last ? "" : key.substring(first + 1, last);
  }

  /**
   * The node of a key.
   *
   * @param key the key
   * @return the node id
   */
  public static String nodeId(final String key) {
    return key.substring(key.lastIndexOf(SEPARATOR) + 1);
  }

  /**
   * Whether a key is for a node of a workflow, whatever the run.
   *
   * @param key the key
   * @param workflowId the workflow
   * @param nodeId the node
   * @return true if both match
   */
  public static boolean isFor(final String key, final String workflowId, final String nodeId) {
    return isIn(key, workflowId) && nodeId(key).equals(nodeId);
  }

  /**
   * Whether a key is for a node of a workflow.
   *
   * @param key the key
   * @param workflowId the workflow
   * @return true if the workflow matches
   */
  public static boolean isIn(final String key, final String workflowId) {
    return key.indexOf(SEPARATOR) >= 0 && workflowId(key).equals(workflowId);
  }
}
