// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** What an assignment targets, read from its scope text. */
export interface Target {
  kind: 'global' | 'server' | 'session' | 'run' | 'workflow';
  sessionId: string | null;
  workflowId: string | null;
  /** Node selectors (`build.*`, `deploy.test`, `group:ci`); empty for the whole workflow. */
  selectors: string[];
}

/** Splits `<session>/<workflow>` (the session is optional). */
const ids = (text: string) => {
  const slash = text.indexOf('/');
  return slash < 0
    ? { sessionId: null, workflowId: text }
    : { sessionId: text.slice(0, slash), workflowId: text.slice(slash + 1) };
};

/**
 * Reads a scope as the server writes it: `global`, `server`, `session:<s>`, `run:[<s>/]<w>`,
 * `workflow:[<s>/]<w>` and `workflow:[<s>/]<w>//<selector>,<selector>`.
 */
export function parseScope(scope: string): Target {
  if (scope === 'global' || scope === 'server') {
    return { kind: scope, sessionId: null, workflowId: null, selectors: [] };
  }
  const colon = scope.indexOf(':');
  const kind = scope.slice(0, colon);
  const rest = scope.slice(colon + 1);
  if (kind === 'session') {
    return { kind, sessionId: rest, workflowId: null, selectors: [] };
  }
  const section = rest.indexOf('//');
  const workflow = section < 0 ? rest : rest.slice(0, section);
  const selectors =
    section < 0
      ? []
      : rest
          .slice(section + 2)
          .split(',')
          .filter(Boolean);
  return {
    kind: kind === 'run' ? 'run' : 'workflow',
    ...ids(workflow),
    selectors,
  };
}

/** A target as a person reads it: "release / release-flow · deploy.*". */
export function targetLabel(target: Target): string {
  if (target.kind === 'global') return 'Everything (observes only)';
  if (target.kind === 'server') return 'The server';
  if (target.kind === 'session') return `Session ${target.sessionId}`;
  const workflow = [target.sessionId, target.workflowId]
    .filter(Boolean)
    .join(' / ');
  if (target.kind === 'run') return `${workflow} · run level`;
  return target.selectors.length === 0
    ? `${workflow} · whole workflow`
    : `${workflow} · ${target.selectors.join(', ')}`;
}

/** The owner of a unit as the ownership map names it: a supervisor id, or `default`. */
export const ownerLabel = (owner: string | undefined | null) =>
  !owner || owner === 'default' ? 'Direct control' : owner;
