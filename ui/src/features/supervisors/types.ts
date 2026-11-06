// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ConfigField } from '@/features/plugins/types';

export type SupervisorCategory =
  | 'GUARD'
  | 'GATE'
  | 'RULES'
  | 'MODEL'
  | 'INTEGRATION'
  | 'COMPOSITION'
  | 'WORKFLOW';

export type SupervisorKind = 'SUPERVISOR' | 'CONTROL_PLANE' | 'COMPOSITE';

/** A supervisor type in the catalog. */
export interface SupervisorTypeSummary {
  type: string;
  displayName: string;
  category: SupervisorCategory;
  kind: SupervisorKind;
  icon: string | null;
}

export interface SupervisorTypeDetails {
  type: string;
  displayName: string;
  description: string;
  usagePattern: string;
  category: SupervisorCategory;
  kind: SupervisorKind;
  configSchema: ConfigField[];
  commonSchema: ConfigField[];
  /** null means every command. */
  defaultAllowedCommands: string[] | null;
  exampleSettings: Record<string, unknown>;
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
}

/** What a supervisor is saved as. */
export interface SupervisorDraft {
  id?: string;
  type: string;
  description?: string;
  settings: Record<string, unknown>;
  dryRun: boolean;
}

export interface ValidationResult {
  valid: boolean;
  errors: { field: string; message: string }[];
}
