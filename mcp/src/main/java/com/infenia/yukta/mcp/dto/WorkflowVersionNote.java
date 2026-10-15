// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * Result of the {@code note_workflow_version} MCP tool.
 *
 * @param workflowId the workflow
 * @param version the version that was annotated
 * @param note the note now on the version, empty when it was cleared
 */
public record WorkflowVersionNote(String workflowId, String version, String note) {}
