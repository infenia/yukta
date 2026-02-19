package com.infenia.jagratha.model;

import com.infenia.jagratha.validation.FilePath;
import com.infenia.jagratha.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @FilePath
        String path,
    @Schema(description = "The unique session identifier", example = "session-123") @SessionId
        String sessionId,
    @Schema(description = "The content of the file (optional)", example = "public class App {}")
        String content) {}
