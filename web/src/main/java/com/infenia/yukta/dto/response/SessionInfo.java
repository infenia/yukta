// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * Session information summary.
 *
 * @param sessionId the session identifier
 * @param workflowCount the number of workflows in this session
 * @param createdAt the creation timestamp
 * @param lastModified the last modification timestamp
 * @param status the session status
 */
@Schema(description = "Session information summary")
public record SessionInfo(
    @Schema(description = "The session identifier") String sessionId,
    @Schema(description = "The number of workflows in this session") int workflowCount,
    @Schema(description = "The creation timestamp") @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,
    @Schema(description = "The last modification timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime lastModified,
    @Schema(description = "The session status") String status) {}
