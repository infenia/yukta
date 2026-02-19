package com.infenia.jagratha.model;

import com.infenia.jagratha.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request object for task execution.
 *
 * @param sessionId the session identifier
 */
@Schema(description = "Request object for triggering quality checks")
public record TaskRequest(
    @Schema(description = "The unique session identifier", example = "session-123") @SessionId
        String sessionId) {}
