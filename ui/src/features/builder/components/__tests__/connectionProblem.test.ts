// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import type { DraftWorkflow } from '../../types';
import { connectionProblem } from '../WorkflowEditorPage.helpers';

const categories = { T: 'TRIGGER', P: 'PROCESSOR', E: 'TERMINAL' };
const edge = (source: string, target: string) => ({
  source,
  target,
  sourcePort: null,
});
const workflow: DraftWorkflow = {
  workflowId: 'wf',
  description: '',
  nodes: [
    { nodeId: 't', type: 'T', config: {} },
    { nodeId: 'a', type: 'P', config: {} },
    { nodeId: 'b', type: 'P', config: {} },
    { nodeId: 'c', type: 'P', config: {} },
    { nodeId: 'end', type: 'E', config: {} },
    { nodeId: 'odd', type: 'UNLOADED', config: {} },
  ],
  edges: [edge('t', 'a'), edge('a', 'b'), edge('b', 'c'), edge('a', 'c')],
};
const problem = (source: string, target: string) =>
  connectionProblem(workflow, categories, source, target);

describe('connectionProblem', () => {
  it('allows an edge the server would accept', () => {
    expect(problem('a', 'end')).toBeNull();
    expect(problem('t', 'b')).toBeNull();
    // a second path to a node is not a loop
    expect(problem('b', 'end')).toBeNull();
  });

  it('refuses a node joined to itself', () => {
    expect(problem('a', 'a')).toBe('An edge cannot connect a node to itself.');
  });

  it('refuses an edge out of a terminal and one into a trigger', () => {
    expect(problem('end', 'a')).toBe(
      'A terminal ends a branch, so nothing can follow it.'
    );
    expect(problem('a', 't')).toBe(
      'A trigger starts a workflow, so nothing can feed into it.'
    );
  });

  it('refuses an edge that closes a loop, however far round', () => {
    expect(problem('c', 'a')).toBe('That edge would make a loop.');
    expect(problem('b', 'a')).toBe('That edge would make a loop.');
  });

  it('does not judge a node whose plugin is unknown', () => {
    expect(problem('odd', 'a')).toBeNull();
    expect(problem('a', 'odd')).toBeNull();
    expect(problem('missing', 'a')).toBeNull();
  });
});
