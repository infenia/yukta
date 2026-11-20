// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { SupervisorRef } from '@/features/supervisors/types';

export interface DraftNode {
  nodeId: string;
  type: string;
  config: Record<string, unknown>;
  /** The stage the node belongs to, for display; absent means its node-ID prefix. */
  group?: string | null;
  /** The palette color of the node, for display; absent means the default look. */
  color?: string | null;
  /** What the node does; the info button shows it instead of the plugin description. */
  description?: string | null;
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
  /** What supervisors own in the workflow; assigned when it is saved. */
  assignments?: SupervisorRef[];
  /** The palette color of a group, by group name. */
  groupColors?: Record<string, string>;
}

export interface SessionDraft {
  sessionId: string;
  name: string;
  description: string;
  initiator: string;
  tags: Record<string, string>;
  projectPath: string;
  workflows: Record<string, DraftWorkflow>;
  /** What supervisors own in the whole session; assigned when it is saved. */
  assignments?: SupervisorRef[];
}

/** One problem the server found: `nodeId` is null for the workflow as a whole, `path` for none in particular. */
export interface ValidationDetail {
  nodeId: string | null;
  path: string | null;
  code: string;
  message: string;
}

export interface ValidationResult {
  valid: boolean;
  message: string | null;
  /** Every problem found, the first of them being \`message\`. */
  problems?: string[];
  /** The same problems with the node and the place in its definition, in the same order. */
  details?: ValidationDetail[];
  outputPorts: Record<string, string[]>;
  /** What would run but probably not as meant; `field` is a config field or an output port. */
  warnings?: { nodeId: string; field: string; message: string }[];
}

/** A bundled example a new session can start from. */
export interface TemplateSummary {
  id: string;
  title: string;
  summary: string;
  pluginTypes: string[];
}

export interface TemplateDetail extends TemplateSummary {
  readme: string;
  /** The session definition to apply; its workflows are kept, its metadata is replaced. */
  session: SessionDraft;
}
