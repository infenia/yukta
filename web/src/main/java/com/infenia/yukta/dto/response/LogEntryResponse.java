// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A structured execution log entry streamed to UI clients.
 *
 * @param executionId the execution identifier the entry belongs to
 * @param pluginId the identifier of the plugin (node) that produced the entry
 * @param pluginName the display name of the plugin that produced the entry
 * @param stream the log stream name (STDOUT, STDERR, or a custom stream name)
 * @param message the log message
 * @param level the log level (DEBUG, INFO, WARN, ERROR)
 * @param timestamp the moment the entry was produced
 */
@Schema(description = "A structured execution log entry streamed to UI clients")
public record LogEntryResponse(
    @Schema(description = "The execution identifier the entry belongs to") String executionId,
    @Schema(description = "The identifier of the plugin (node) that produced the entry")
        String pluginId,
    @Schema(description = "The display name of the plugin that produced the entry")
        String pluginName,
    @Schema(description = "The log stream name (STDOUT, STDERR, or a custom stream name)")
        String stream,
    @Schema(description = "The log message") String message,
    @Schema(description = "The log level (DEBUG, INFO, WARN, ERROR)") String level,
    @Schema(description = "The moment the entry was produced") Instant timestamp) {}
