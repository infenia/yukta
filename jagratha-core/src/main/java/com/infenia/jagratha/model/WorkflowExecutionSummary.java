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
package com.infenia.jagratha.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * Summary of a workflow execution.
 *
 * @param executionId the unique execution identifier
 * @param workflowId the workflow identifier
 * @param status the execution status
 * @param startTime the start time of the execution
 * @param endTime the end time of the execution (if finished)
 */
@Schema(description = "Summary of a workflow execution")
public record WorkflowExecutionSummary(
    @Schema(description = "The unique execution identifier") String executionId,
    @Schema(description = "The workflow identifier") String workflowId,
    @Schema(description = "The execution status") String status,
    @Schema(description = "The start time of the execution") LocalDateTime startTime,
    @Schema(description = "The end time of the execution") LocalDateTime endTime) {}
