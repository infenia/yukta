// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of the {@code note_workflow_version} MCP tool.
 *
 * @param workflowId the workflow
 * @param version the version that was annotated
 * @param note the note now on the version, empty when it was cleared
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowVersionNote(String workflowId, String version, String note) {}
