// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import java.util.Map;

/**
 * Internal record for session configuration state stored in SessionConfigStore.
 *
 * @param projectPath the project path for the session
 * @param initiator the initiator name
 * @param initiatedTime the timestamp when the session was initiated
 * @param tags additional tags for the session
 * @param description a human-readable description of the session
 */
public record SessionConfig(
    String projectPath,
    String initiator,
    String initiatedTime,
    Map<String, String> tags,
    String description) {

  /** Compact constructor for immutability. */
  public SessionConfig {
    tags = tags != null ? Map.copyOf(tags) : Map.of();
  }
}
