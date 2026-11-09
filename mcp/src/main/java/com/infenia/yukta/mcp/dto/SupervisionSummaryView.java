// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.List;

/**
 * How much of the server nobody supervises.
 *
 * @param unowned how many workflows nobody owns (the default supervisor carries them)
 * @param unownedWorkflows the first of them, at most 100
 * @param conflicts how many assignments start-up disabled because they overlapped another
 */
public record SupervisionSummaryView(
    int unowned, List<UnownedWorkflow> unownedWorkflows, long conflicts) {

  /** Keeps an unmodifiable copy of the workflows. */
  public SupervisionSummaryView {
    unownedWorkflows = List.copyOf(unownedWorkflows);
  }

  /**
   * A workflow nobody owns.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   */
  public record UnownedWorkflow(String sessionId, String workflowId) {}
}
