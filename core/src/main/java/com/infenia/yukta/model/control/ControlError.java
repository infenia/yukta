// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import java.time.Instant;

/**
 * Standard error message for the Control Bus.
 *
 * @param nodeId the unique identifier of the node
 * @param executionId the execution identifier
 * @param reason the failure reason
 * @param detail detailed exception message
 * @param timestamp the time the exception occurred
 */
public record ControlError(
    String nodeId, String executionId, String reason, String detail, Instant timestamp) {
  /** Create a new error record with current timestamp. */
  public ControlError(
      final String nodeId, final String executionId, final String reason, final String detail) {
    this(nodeId, executionId, reason, detail, Instant.now());
  }
}
