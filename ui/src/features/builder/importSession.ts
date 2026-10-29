// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { DraftWorkflow, SessionDraft } from './types';

/** What the server writes in place of a secret in an export (ConfigRedactor.PLACEHOLDER). */
export const REDACTION_MARKER = '******';

/** Parses the text as a session, or says what is wrong with it, with line and column for bad JSON. */
export function parseSession(
  text: string
): { session: SessionDraft } | { error: string } {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (e) {
    const position = /position (\d+)/.exec((e as Error).message);
    if (position) {
      const before = text.slice(0, Number(position[1]));
      const line = before.split('\n').length;
      const column = before.length - before.lastIndexOf('\n');
      return { error: `Invalid JSON at line ${line}, column ${column}.` };
    }
    return { error: `Invalid JSON: ${(e as Error).message}` };
  }
  const session = value as Partial<SessionDraft> | null;
  if (
    typeof session?.sessionId !== 'string' ||
    typeof session.workflows !== 'object' ||
    session.workflows === null
  ) {
    return { error: 'This is not a session: it needs a sessionId and workflows.' };
  }
  return { session: session as SessionDraft };
}

/** How one workflow differs from the one on the server: what the import adds, removes or changes. */
export function diffWorkflow(
  existing: DraftWorkflow | undefined,
  imported: DraftWorkflow
): string[] {
  if (!existing) return ['new workflow'];
  const old = new Map(existing.nodes.map((n) => [n.nodeId, JSON.stringify(n)]));
  const next = new Map(imported.nodes.map((n) => [n.nodeId, JSON.stringify(n)]));
  const edgeKey = (e: DraftWorkflow['edges'][number]) =>
    `${e.source}${e.sourcePort ? `:${e.sourcePort}` : ''} -> ${e.target}`;
  const oldEdges = new Set(existing.edges.map(edgeKey));
  const newEdges = new Set(imported.edges.map(edgeKey));
  return [
    ...(existing.description === imported.description
      ? []
      : ['description changed']),
    ...[...next.keys()].filter((id) => !old.has(id)).map((id) => `+ node ${id}`),
    ...[...old.keys()].filter((id) => !next.has(id)).map((id) => `- node ${id}`),
    ...[...next.keys()]
      .filter((id) => old.has(id) && old.get(id) !== next.get(id))
      .map((id) => `~ node ${id}`),
    ...[...newEdges].filter((e) => !oldEdges.has(e)).map((e) => `+ edge ${e}`),
    ...[...oldEdges].filter((e) => !newEdges.has(e)).map((e) => `- edge ${e}`),
  ];
}
