// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A directed edge between two workflow DAG nodes.
 *
 * @param source the source node identifier
 * @param target the target node identifier
 * @param sourcePort the output port on the source node, or null to route all messages
 */
@Schema(description = "A directed edge between two workflow DAG nodes")
public record WorkflowGraphEdge(
    @Schema(description = "The source node identifier") String source,
    @Schema(description = "The target node identifier") String target,
    @Schema(
            description = "The output port on the source node, null routes all messages",
            nullable = true)
        String sourcePort) {}
