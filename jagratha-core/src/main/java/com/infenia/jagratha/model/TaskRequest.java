package com.infenia.jagratha.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request object for task execution.
 *
 * @param sessionId the session identifier
 */
@Schema(description = "Request object for triggering quality checks")
public record TaskRequest(
    @Schema(description = "The unique session identifier", example = "session-123")
        @NotBlank(message = "Session ID is required")
        @Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid session ID format")
        String sessionId) {}
