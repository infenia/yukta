// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { SupervisionSummary } from '../api/supervision';
import type { AttachmentSource, AttachmentView, Decision } from '../types';
import { parseScope, targetLabel } from './ownership';
import { workflowKey } from './supervisionGraph';

/** One controller of a workflow, and how it is attached. */
export interface Controller {
  supervisorId: string;
  /** The whole run, the whole workflow, or only some of its nodes. */
  level: 'run' | 'workflow' | 'nodes';
  enabled: boolean;
  source: AttachmentSource;
}

/** A workflow and who controls it. */
export interface WorkflowRow {
  /** `<session>/<workflow>`; the session is empty for a `workflow:<w>` assignment. */
  key: string;
  sessionId: string | null;
  workflowId: string;
  controllers: Controller[];
  /** Nobody owns it: the server lists it under what Direct control controls. */
  direct: boolean;
  /** The supervisor commands on workflows of this name in the newest decisions. */
  actions: { count: number; newest: string | null };
}

/** An assignment that names no workflow: a whole session, or everything. */
export interface AcrossRow {
  id: string;
  supervisorId: string;
  label: string;
}

/**
 * Every workflow the server knows a controller for, built from the assignments, the workflows
 * nobody owns and the newest decisions: workflows with a switched-off assignment first, then the
 * ones acted on most recently, then by name.
 *
 * ponytail: a decision carries no session, so its count is shared by same-named workflows of
 * different sessions (add `sessionId` to the decision to fix it); workflows owned only through a
 * session or global assignment get no row of their own (a `/api/supervision/workflows` endpoint
 * would list them); and the list stops at the 100 unowned workflows the server returns.
 */
export function workflowIndex(
  attachments: AttachmentView[],
  summary: SupervisionSummary,
  decisions: Decision[]
): { rows: WorkflowRow[]; across: AcrossRow[] } {
  const rows = new Map<string, WorkflowRow>();
  const across: AcrossRow[] = [];
  const row = (sessionId: string | null, workflowId: string) => {
    const key = workflowKey({ sessionId, workflowId });
    const found = rows.get(key);
    if (found) return found;
    const created: WorkflowRow = {
      key,
      sessionId,
      workflowId,
      controllers: [],
      direct: false,
      actions: { count: 0, newest: null },
    };
    rows.set(key, created);
    return created;
  };
  for (const a of attachments) {
    const t = parseScope(a.scope);
    if (t.workflowId && (t.kind === 'workflow' || t.kind === 'run')) {
      row(t.sessionId, t.workflowId).controllers.push({
        supervisorId: a.supervisorId,
        level:
          t.kind === 'run'
            ? 'run'
            : t.selectors.length > 0
              ? 'nodes'
              : 'workflow',
        enabled: a.enabled,
        source: a.source,
      });
    } else {
      across.push({
        id: a.id,
        supervisorId: a.supervisorId,
        label: targetLabel(t),
      });
    }
  }
  for (const w of summary.unownedWorkflows) {
    row(w.sessionId, w.workflowId).direct = true;
  }
  for (const r of rows.values()) {
    for (const d of decisions) {
      if (d.workflowId !== r.workflowId) continue;
      r.actions.count += 1;
      if (!r.actions.newest || d.time > r.actions.newest) {
        r.actions.newest = d.time;
      }
    }
  }
  const disabled = (r: WorkflowRow) => r.controllers.some((c) => !c.enabled);
  const sorted = [...rows.values()].sort(
    (a, b) =>
      Number(disabled(b)) - Number(disabled(a)) ||
      (b.actions.newest ?? '').localeCompare(a.actions.newest ?? '') ||
      a.key.localeCompare(b.key)
  );
  return { rows: sorted, across };
}
