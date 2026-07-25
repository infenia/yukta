// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A workflow summary enriched with node/edge counts and latest execution status.
 *
 * <p>Used in the session details page workflow grid. Status is nullable: null means the workflow
 * has never been executed; a string value (e.g. "RUNNING", "SUCCESS") is an actual execution
 * record.
 *
 * @param workflowId the workflow identifier
 * @param description a human-readable description of the workflow
 * @param nodeCount the number of nodes in the workflow DAG
 * @param edgeCount the number of edges in the workflow DAG
 * @param status the latest execution status (PENDING, RUNNING, SUCCESS, FAILURE, ERROR), or null if
 *     never executed
 */
@Schema(description = "Workflow summary with node/edge counts and execution status")
public record WorkflowSummary(
    @Schema(description = "The workflow identifier", example = "wf-ingest-main") String workflowId,
    @Schema(description = "Description of the workflow") String description,
    @Schema(description = "Number of nodes in the workflow DAG", example = "15") int nodeCount,
    @Schema(description = "Number of edges in the workflow DAG", example = "12") int edgeCount,
    @Schema(
            description =
                "Latest execution status (PENDING, RUNNING, SUCCESS, FAILURE, ERROR), "
                    + "or null if the workflow has never been executed",
            nullable = true,
            example = "SUCCESS")
        String status) {}
