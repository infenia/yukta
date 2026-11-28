// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * One past event of the activity feed.
 *
 * @param seq the entry's number; pass the lowest one received as {@code before} for the next page
 * @param envelope the event as {@code GET /api/events/stream} sends it
 */
public record ActivityHistoryEntry(
    long seq,
    @Schema(type = "object", description = "The event envelope of the live stream")
        JsonNode envelope) {}
