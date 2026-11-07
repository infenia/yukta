// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { AttachmentView } from '../types';

export type CoverageLevel = 'global' | 'session' | 'workflow' | 'section';

/** One place in the Global > Session > Workflow > Section nesting, with who is attached right there. */
export interface CoverageNode {
  key: string;
  level: CoverageLevel;
  label: string;
  own: AttachmentView[];
  children: CoverageNode[];
}

const SECTION = '//';

/** Where a scope sits: its ancestors, outermost first, then itself. */
function path(
  scope: string
): { key: string; level: CoverageLevel; label: string }[] {
  if (scope === 'global') return [];
  const colon = scope.indexOf(':');
  const rest = scope.slice(colon + 1);
  if (scope.startsWith('session:')) {
    return [{ key: scope, level: 'session', label: rest }];
  }
  const at = rest.indexOf(SECTION);
  const workflow = at < 0 ? rest : rest.slice(0, at);
  const slash = workflow.indexOf('/');
  const result: ReturnType<typeof path> = [];
  if (slash >= 0) {
    const session = workflow.slice(0, slash);
    result.push({
      key: `session:${session}`,
      level: 'session',
      label: session,
    });
  }
  result.push({
    key: `workflow:${workflow}`,
    level: 'workflow',
    label: slash >= 0 ? workflow.slice(slash + 1) : workflow,
  });
  if (at >= 0) {
    result.push({
      key: scope,
      level: 'section',
      label: rest
        .slice(at + SECTION.length)
        .split(',')
        .join(', '),
    });
  }
  return result;
}

/** Groups attachments into the scope nesting; the root is Global. */
export function buildCoverage(attachments: AttachmentView[]): CoverageNode {
  const root: CoverageNode = {
    key: 'global',
    level: 'global',
    label: 'Everything',
    own: [],
    children: [],
  };
  const byKey = new Map<string, CoverageNode>([[root.key, root]]);
  for (const attachment of attachments) {
    let parent = root;
    for (const step of path(attachment.scope)) {
      let node = byKey.get(step.key);
      if (!node) {
        node = { ...step, own: [], children: [] };
        byKey.set(step.key, node);
        parent.children.push(node);
      }
      parent = node;
    }
    parent.own.push(attachment);
  }
  return root;
}
