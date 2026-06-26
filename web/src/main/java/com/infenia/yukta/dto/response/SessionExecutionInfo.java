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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Record representing execution information for a session.
 *
 * @param sessionId the session identifier
 * @param activeExecutions the number of active executions
 * @param totalWorkflows the total number of workflows
 */
@Schema(description = "Record representing execution information for a session")
public record SessionExecutionInfo(
    @Schema(description = "The session identifier", example = "session-123") String sessionId,
    @Schema(description = "The number of active executions", example = "5") int activeExecutions,
    @Schema(description = "The total number of workflows", example = "10") int totalWorkflows) {}
