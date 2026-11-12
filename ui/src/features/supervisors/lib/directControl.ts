// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { SupervisionSummary } from '../api/supervision';

/** The workflows under direct control: what no supervisor owns. */
export interface DirectControl {
  owned: { sessionId: string; workflowId: string }[];
  /** The one who decides: people, and agents sending commands themselves. */
  decisionMaker: { name: string; icon: string };
  /** Direct control has no parents and no children. */
  parents: string[];
  children: string[];
}

/** Direct control as a supervisor, built from the supervision summary. */
export const directControl = (summary: SupervisionSummary): DirectControl => ({
  owned: summary.unownedWorkflows,
  decisionMaker: { name: 'People and agents', icon: 'person' },
  parents: [],
  children: [],
});
