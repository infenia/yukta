// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * Result of starting a workflow execution via MCP.
 *
 * @param executionId the unique identifier of the execution
 * @param reusedExecution true when a start without an idempotencyKey was merged into an earlier
 *     start of the same workflow and {@code executionId} is that earlier run; start again with a
 *     fresh idempotencyKey to force a new run
 */
public record WorkflowStartResult(String executionId, boolean reusedExecution) {}
