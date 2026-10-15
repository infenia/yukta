// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * Result of the {@code rollback_workflow} MCP tool.
 *
 * @param workflowId the workflow
 * @param version the version that is now the current definition (as a new save)
 */
public record WorkflowRollback(String workflowId, String version) {}
