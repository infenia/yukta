// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export interface SessionListItem {
  sessionId: string;
  name: string;
  description: string;
  initiator: string;
  tags: string[];
  projectPath: string;
  workflowCount: number;
}

export interface SessionListItems {
  sessions: SessionListItem[];
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

export type WorkflowExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILURE' | 'ERROR';

export interface WorkflowSummary {
  workflowId: string;
  description: string;
  nodeCount: number;
  edgeCount: number;
  status: WorkflowExecutionStatus | null;
}

export interface WorkflowSummaries {
  workflows: WorkflowSummary[];
}
