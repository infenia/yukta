package com.infenia.jagratha.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request object for task execution.
 *
 * @param sessionId the session identifier
 */
public record TaskRequest(
    @NotBlank(message = "Session ID is required")
        @Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid session ID format")
        String sessionId) {}
