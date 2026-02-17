package io.jagratha.jagratha.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request object for file operations.
 *
 * @param path the file path
 * @param sessionId the session identifier
 */
public record FileRequest(
    @NotBlank(message = "Path is required") String path,
    @NotBlank(message = "Session ID is required") String sessionId) {}
