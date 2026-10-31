// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { SessionDraft } from '../types';

/** A session needs a workflow, so a new one starts with a trigger wired to a console terminal. */
export function starterSession(
  fields: Omit<SessionDraft, 'workflows' | 'tags'>
): SessionDraft {
  return {
    ...fields,
    tags: {},
    workflows: {
      main: {
        workflowId: 'main',
        description: 'Starter workflow',
        nodes: [
          { nodeId: 'start', type: 'API_TRIGGER', config: {} },
          { nodeId: 'out', type: 'CONSOLE_TERMINAL', config: {} },
        ],
        edges: [{ source: 'start', target: 'out', sourcePort: null }],
      },
    },
  };
}

/** A session ID made from a name: lower case, with every run of other characters one dash. */
export function slugify(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}
