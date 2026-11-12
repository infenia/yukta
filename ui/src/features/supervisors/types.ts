// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ConfigField } from '@/features/plugins/types';

/** Where a decision maker decides. */
export type SupervisorCategory = 'RULES' | 'EXTERNAL_SYSTEM' | 'ON_THIS_SERVER';

/** A decision maker in the catalog. */
export interface SupervisorTypeSummary {
  type: string;
  displayName: string;
  category: SupervisorCategory;
  icon: string | null;
}

export interface SupervisorTypeDetails {
  type: string;
  displayName: string;
  description: string;
  usagePattern: string;
  category: SupervisorCategory;
  configSchema: ConfigField[];
  commonSchema: ConfigField[];
  /** null means every command. */
  defaultAllowedCommands: string[] | null;
  exampleSettings: Record<string, unknown>;
  /** The signals a supervisor of this type is told: EVENT, TICK, REVIEW, DELEGATED, CONSULTED, STARTED. */
  signals?: string[];
  /** Whether it may act on its own. */
  canInitiate?: boolean;
}

/** Ready-made settings of a decision maker. */
export interface SupervisorPreset {
  name: string;
  description: string;
  settings: Record<string, unknown>;
}

/** A configured supervisor; secrets come back as `***`. */
export interface SupervisorView {
  id: string;
  type: string;
  description: string | null;
  settings: Record<string, unknown>;
  dryRun: boolean;
  /** True for a supervisor the server configures itself: read-only. */
  system: boolean;
  attachments: number;
  applied: number;
  rejected: number;
  recorded: number;
  rateLimited: number;
  /** The supervisors it reports to, in the order they are asked. */
  parents?: string[];
  /** How each kind of delegation (APPROVAL, INPUT, CONSULT) is asked of the parents. */
  delegation?: Record<string, { mode: string; timeout?: string | null }>;
}

/** Who carries each unit of a workflow: a supervisor id, or `default`. */
export interface Ownership {
  sessionId: string;
  workflowId: string;
  defaultSupervisor: boolean;
  run: string;
  nodes: Record<string, string>;
}

export type AttachmentSource = 'MANUAL' | 'DEFINITION' | 'SYSTEM';

/** An attachment of a supervisor to a scope. */
export interface AttachmentView {
  id: string;
  supervisorId: string;
  /** `global`, `session:<id>`, `workflow:<s>/<w>` or a section `workflow:<s>/<w>//<selector>,...`. */
  scope: string;
  enabled: boolean;
  source: AttachmentSource;
  originSessionId: string | null;
  originWorkflowId: string | null;
  /** Changes only when the request asks to unlock. */
  locked?: boolean;
}

/** What a supervisor is saved as. */
export interface SupervisorDraft {
  id?: string;
  type: string;
  description?: string;
  settings: Record<string, unknown>;
  dryRun: boolean;
  /** The supervisors it reports to. */
  parents?: string[];
}

/** A supervisor declared in a session or workflow definition. */
export interface SupervisorRef {
  supervisor: string;
  /** Workflow definitions only: node ids or \`<prefix>.*\`; absent means the whole workflow. */
  nodes?: string[] | null;
  enabled?: boolean | null;
}

/** What became of one command a supervisor emitted. */
export interface Decision {
  time: string;
  supervisorId: string;
  outcome:
    | 'applied'
    | 'rejected'
    | 'dry-run'
    | 'rate-limited'
    | 'forbidden'
    | 'out-of-scope'
    | 'arbitrated'
    | 'coalesced'
    | 'flapping-blocked'
    | 'not-owner'
    | 'awaiting-approval'
    // a newer server may name more; the UI shows them in a neutral colour
    | (string & {});
  command: string;
  executionId: string;
  /** Why it was not applied, or null. */
  reason: string | null;
  /** The node it addressed, or null for a run-level or server command. */
  nodeId?: string | null;
  /** What made the supervisor send it: event, answers, tick or initiative. */
  cause?: string | null;
  /** The directive's key; the runs of one selector share it before `#`. */
  key?: string | null;
  /** The reason the supervisor gave for sending it. */
  because?: string | null;
  /** The workflow of the run, or null when it was not known. */
  workflowId?: string | null;
}
