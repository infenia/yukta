// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ExecutionSummary } from '@/features/workflow/types/workflow';

export interface SessionListItem {
  sessionId: string;
  name: string;
  description: string;
  initiator: string;
  tags: string[];
  projectPath: string;
  workflowCount: number;
  /** The session's newest run; absent when it never ran. */
  lastRun?: ExecutionSummary | null;
}

export interface SessionDetails {
  sessionId: string;
  name: string;
  description: string;
  initiator: string;
  tags: string[];
  projectPath: string;
  workflowIds: string[];
}

export type WorkflowExecutionStatus =
  'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILURE' | 'ERROR' | 'STOPPED';

export interface WorkflowSummary {
  workflowId: string;
  description: string;
  nodeCount: number;
  edgeCount: number;
  status: WorkflowExecutionStatus | null;
}
