// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * Result of starting a workflow execution via MCP.
 *
 * @param executionId the unique identifier of the started execution
 */
public record WorkflowStartResult(String executionId) {}
