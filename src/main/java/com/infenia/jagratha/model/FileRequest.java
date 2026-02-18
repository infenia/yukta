package com.infenia.jagratha.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request object for file operations.
 *
 * @param path the file path
 * @param sessionId the session identifier
 * @param content the file content (optional)
 */
public record FileRequest(
    @NotBlank(message = "Path is required") String path,
    @NotBlank(message = "Session ID is required")
        @Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid session ID format")
        String sessionId,
    String content) {}
