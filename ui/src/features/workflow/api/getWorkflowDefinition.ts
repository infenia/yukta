// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

export interface DefinitionNode {
  nodeId: string;
  type: string;
  /** Literal secrets arrive already masked by the server. */
  config?: Record<string, unknown>;
}

export interface WorkflowDefinition {
  workflowId: string;
  nodes: DefinitionNode[];
}

export const getWorkflowDefinition = (sessionId: string, workflowId: string) =>
  fetchApi<WorkflowDefinition>(
    api`/api/sessions/${sessionId}/workflows/${workflowId}`
  );
