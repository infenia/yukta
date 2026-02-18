package com.infenia.jagratha.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response object for task execution.
 *
 * @param status the task status (e.g., SUCCESS, FAILURE)
 * @param output the task output or error message
 */
@Schema(description = "Response object containing the results of quality checks")
public record TaskResponse(
    @Schema(description = "The status of the task", example = "SUCCESS") String status,
    @Schema(description = "The detailed output or error message from the task execution")
        String output) {}
