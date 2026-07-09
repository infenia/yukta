// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Summary of a session.
 *
 * @param sessionId the session identifier
 * @param initiator the initiator of the session
 * @param initiatedTime the time the session was initiated
 * @param lastActiveTime the time of the most recent activity in the session
 * @param description the session description
 * @param tags the session tags
 */
@Schema(description = "Summary of a session")
public record SessionSummary(
    @Schema(description = "The session identifier") String sessionId,
    @Schema(description = "The initiator of the session") String initiator,
    @Schema(description = "The time the session was initiated") String initiatedTime,
    @Schema(description = "The time of the most recent activity in the session")
        LocalDateTime lastActiveTime,
    @Schema(description = "The session description") String description,
    @Schema(description = "The session tags") Map<String, String> tags) {
  /** Compact constructor. */
  public SessionSummary {
    tags = tags != null ? Map.copyOf(tags) : Map.of();
  }
}
