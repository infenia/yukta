package io.jagratha.jagratha.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request object for task execution.
 *
 * @param sessionId the session identifier
 */
public record TaskRequest(@NotBlank(message = "Session ID is required") String sessionId) {}
