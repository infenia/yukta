// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.infenia.yukta.model.control.PendingApproval;
import java.util.List;

/**
 * The nodes of a session that wait for a signal, returned by the {@code list_pending_approvals} MCP
 * tool.
 *
 * @param sessionId the session
 * @param approvals one entry per open wait, oldest first
 */
public record PendingApprovals(String sessionId, List<PendingApproval> approvals) {

  /** Compact constructor to ensure immutability. */
  public PendingApprovals {
    approvals = approvals == null ? List.of() : List.copyOf(approvals);
  }
}
