// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import java.time.Instant;

/**
 * Standard heartbeat message for the Control Bus.
 *
 * @param nodeId the unique identifier of the node
 * @param uptime the uptime of the node in milliseconds
 * @param timestamp the time the heartbeat was emitted
 */
public record ControlHeartbeat(String nodeId, long uptime, Instant timestamp) {
  /** Create a new heartbeat with current timestamp. */
  public ControlHeartbeat(final String nodeId, final long uptime) {
    this(nodeId, uptime, Instant.now());
  }
}
