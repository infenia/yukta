// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import com.infenia.yukta.service.control.ExecutionRejectedException;

/** A start was sent to a standby; the caller should retry on the leader (HTTP 503). */
public class NotLeaderException extends ExecutionRejectedException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public NotLeaderException() {
    super("This server is a standby; retry on the leader");
  }
}
