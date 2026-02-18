package com.infenia.jagratha.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request object for file operations.
 *
 * @param path the file path
 * @param sessionId the session identifier
 * @param content the file content (optional)
 */
@Schema(description = "Request object for logging a file path")
public record FileRequest(
    @Schema(description = "The relative path of the file", example = "src/main/java/App.java")
        @NotBlank(message = "Path is required")
        String path,
    @Schema(description = "The unique session identifier", example = "session-123")
        @NotBlank(message = "Session ID is required")
        @Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid session ID format")
        String sessionId,
    @Schema(description = "The content of the file (optional)", example = "public class App {}")
        String content) {}
