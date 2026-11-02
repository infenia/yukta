// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import java.util.List;

/**
 * What the supervisors together say about a control command: it may run, it is refused, or it may
 * run once someone approves it.
 */
@SuppressWarnings("PMD.ConstantsInInterface")
public sealed interface ReviewVerdict {

  /** Every supervisor allows the command. */
  ReviewVerdict ALLOWED = new Allowed();

  /** The command may run. */
  record Allowed() implements ReviewVerdict {}

  /**
   * A supervisor refused the command.
   *
   * @param reason {@code <supervisor>: <reason>}
   */
  record Denied(String reason) implements ReviewVerdict {}

  /**
   * One or more supervisors want the command approved first, and none refused it.
   *
   * @param need the merged need: the highest risk, the first route named and the shortest ttl
   * @param reason the reasons, each as {@code <supervisor>: <reason>}, joined with {@code "; "}
   * @param askedBy the ids of the supervisors that asked, in review order
   */
  record NeedsApproval(ApprovalNeed need, String reason, List<String> askedBy)
      implements ReviewVerdict {

    /**
     * Copies the list.
     *
     * @param need the merged need
     * @param reason the joined reasons
     * @param askedBy the supervisors that asked
     */
    public NeedsApproval {
      askedBy = List.copyOf(askedBy);
    }
  }
}
