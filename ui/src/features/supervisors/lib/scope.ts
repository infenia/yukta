// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Separates the workflow from the node selectors in a section scope. */
const SECTION = '//';

/** A scope as a person reads it. */
export function scopeLabel(scope: string): string {
  if (scope === 'global') return 'Global';
  const [kind, rest] = [
    scope.split(':')[0],
    scope.slice(scope.indexOf(':') + 1),
  ];
  if (kind === 'session') return `Session ${rest}`;
  const at = rest.indexOf(SECTION);
  return at < 0
    ? `Workflow ${rest}`
    : `Section ${rest.slice(0, at)}: ${rest
        .slice(at + SECTION.length)
        .split(',')
        .join(', ')}`;
}

/**
 * The node selectors typed as text: separated by commas or spaces, blanks dropped. A selector is a
 * node id or `<prefix>.*`.
 */
export function parseSelectors(text: string): string[] {
  return text
    .split(/[\s,]+/)
    .map((selector) => selector.trim())
    .filter((selector) => selector !== '');
}

/**
 * The scope text for a kind and its ids, or null when an id is missing. A section also needs at
 * least one node selector.
 */
export function buildScope(
  kind: 'global' | 'session' | 'workflow' | 'section',
  sessionId: string,
  workflowId: string,
  nodes = ''
): string | null {
  if (kind === 'global') return 'global';
  if (kind === 'session') return sessionId ? `session:${sessionId}` : null;
  if (!workflowId) return null;
  const workflow = sessionId
    ? `workflow:${sessionId}/${workflowId}`
    : `workflow:${workflowId}`;
  if (kind === 'workflow') return workflow;
  const selectors = parseSelectors(nodes);
  return selectors.length === 0
    ? null
    : `${workflow}${SECTION}${selectors.join(',')}`;
}
