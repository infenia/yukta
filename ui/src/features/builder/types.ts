// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export interface DraftNode {
  nodeId: string;
  type: string;
  config: Record<string, unknown>;
  /** The stage the node belongs to, for display; absent means its node-ID prefix. */
  group?: string | null;
}

export interface DraftEdge {
  source: string;
  target: string;
  sourcePort: string | null;
}

export interface DraftWorkflow {
  workflowId: string;
  description: string;
  nodes: DraftNode[];
  edges: DraftEdge[];
}

export interface SessionDraft {
  sessionId: string;
  name: string;
  description: string;
  initiator: string;
  tags: Record<string, string>;
  projectPath: string;
  workflows: Record<string, DraftWorkflow>;
}

export interface ValidationResult {
  valid: boolean;
  message: string | null;
  outputPorts: Record<string, string[]>;
}
