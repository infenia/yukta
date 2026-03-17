/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.model.monitoring;

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
