// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { ExecutionSummary, WorkflowProgress } from '../types/workflow';

export async function getWorkflowProgress(
  executionId: string
): Promise<WorkflowProgress> {
  return fetchApi<WorkflowProgress>(api`/api/executions/${executionId}`);
}

/** A run of the tree below an execution, with the run that started it. */
export interface TreeRun extends ExecutionSummary {
  parentExecutionId: string | null;
}

/** The execution and every run it started as a sub-workflow, at any depth, oldest first. */
export function getExecutionTree(executionId: string): Promise<TreeRun[]> {
  return fetchApi<TreeRun[]>(api`/api/executions/${executionId}/tree`);
}
