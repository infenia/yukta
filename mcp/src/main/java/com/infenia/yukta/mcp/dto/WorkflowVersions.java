// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.infenia.yukta.service.workflow.store.DefinitionRevision;
import java.util.List;

/**
 * The saved versions of a workflow, returned by the {@code list_workflow_versions} MCP tool.
 *
 * @param sessionId the session
 * @param workflowId the workflow
 * @param versions the versions, newest first (empty if the store keeps none)
 */
public record WorkflowVersions(
    String sessionId, String workflowId, List<DefinitionRevision> versions) {

  /** Compact constructor to ensure immutability. */
  public WorkflowVersions {
    versions = versions == null ? List.of() : List.copyOf(versions);
  }
}
