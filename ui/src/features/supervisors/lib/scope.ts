// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Separates the workflow from the node selectors in a section scope. */
const SECTION = '//';

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

/** The part of a node id before its first dot: the group the picker puts it under. */
const prefixOf = (nodeId: string) =>
  nodeId.includes('.') ? nodeId.slice(0, nodeId.indexOf('.')) : '';

/**
 * Node ids grouped as the server groups them for `group:<name>`: a node's declared group, else
 * the part of its id before the first dot; in the order they first appear.
 */
export function groupNodes(
  ids: string[],
  groupOf: Record<string, string> = {}
): [string, string[]][] {
  const groups = new Map<string, string[]>();
  for (const id of ids) {
    const group = groupOf[id] || prefixOf(id);
    groups.set(group, [...(groups.get(group) ?? []), id]);
  }
  return [...groups.entries()];
}
