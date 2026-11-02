// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import lombok.Getter;

/**
 * A control command was not applied yet: a supervisor's review wants it approved first, so it is
 * parked and an {@code APPROVAL} input request was raised. Not a failure; REST answers it with
 * {@code 202 Accepted} and the request id, and the command runs once the request is approved.
 */
@Getter
public class ControlAwaitingApprovalException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The status a client is shown. */
  public static final String STATUS = "AWAITING_APPROVAL";

  /** The input request that decides. */
  private final String requestId;

  /**
   * Creates the exception.
   *
   * @param requestId the input request that decides
   * @param reason why approval is needed, safe to show to API clients
   */
  public ControlAwaitingApprovalException(final String requestId, final String reason) {
    super("Awaiting approval in input request " + requestId + ": " + reason, null, false, false);
    this.requestId = requestId;
  }
}
