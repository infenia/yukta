// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { DraftWorkflow } from '../types';

/** What sessionStorage holds: the draft, and the definition it was made from. */
interface Stored {
  base: string;
  draft: DraftWorkflow;
}

const keyOf = (sessionId: string, workflowId: string) =>
  `yukta.draft.${sessionId}/${workflowId}`;

/**
 * The unsaved edits kept for this workflow, or null. A draft made from an older definition than
 * the one just loaded is dropped: restoring it would silently undo someone else's save.
 */
export function readDraft(
  sessionId: string,
  workflowId: string,
  loaded: DraftWorkflow
): DraftWorkflow | null {
  try {
    const raw = sessionStorage.getItem(keyOf(sessionId, workflowId));
    if (!raw) return null;
    const stored = JSON.parse(raw) as Stored;
    if (stored.base === JSON.stringify(loaded)) return stored.draft;
    sessionStorage.removeItem(keyOf(sessionId, workflowId));
  } catch {
    // storage blocked or unreadable: no draft
  }
  return null;
}

/** Keeps the draft, or forgets it when it is null. */
export function writeDraft(
  sessionId: string,
  workflowId: string,
  loaded: DraftWorkflow,
  draft: DraftWorkflow | null
): void {
  try {
    if (draft) {
      const stored: Stored = { base: JSON.stringify(loaded), draft };
      sessionStorage.setItem(
        keyOf(sessionId, workflowId),
        JSON.stringify(stored)
      );
    } else {
      sessionStorage.removeItem(keyOf(sessionId, workflowId));
    }
  } catch {
    // storage full or blocked: the draft is only in memory, as before
  }
}
