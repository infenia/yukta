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
