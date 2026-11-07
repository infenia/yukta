// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { buildCoverage } from '../coverage';
import { capabilities, commandLabel } from '../capabilities';
import type { AttachmentView } from '../../types';

const att = (id: string, scope: string): AttachmentView => ({
  id,
  supervisorId: id,
  scope,
  enabled: true,
  source: 'MANUAL',
  originSessionId: null,
  originWorkflowId: null,
});

describe('buildCoverage', () => {
  it('nests global > session > workflow > section and keeps who is attached where', () => {
    const root = buildCoverage([
      att('g', 'global'),
      att('s', 'session:ci'),
      att('w', 'workflow:ci/build'),
      att('x', 'workflow:ci/build//compile.*,test'),
      att('y', 'workflow:ci/deploy'),
    ]);
    expect(root.own.map((a) => a.id)).toEqual(['g']);
    const [ci] = root.children;
    expect(ci.label).toBe('ci');
    expect(ci.own.map((a) => a.id)).toEqual(['s']);
    expect(ci.children.map((c) => c.label)).toEqual(['build', 'deploy']);
    const build = ci.children[0];
    expect(build.own.map((a) => a.id)).toEqual(['w']);
    expect(build.children[0]).toMatchObject({
      level: 'section',
      label: 'compile.*, test',
    });
  });

  it('puts a workflow without a session at the top', () => {
    const root = buildCoverage([att('w', 'workflow:build')]);
    expect(root.children[0]).toMatchObject({
      level: 'workflow',
      label: 'build',
    });
  });
});

describe('capabilities', () => {
  it('applies the server defaults', () => {
    expect(capabilities({ settings: {}, dryRun: false })).toEqual({
      commands: null,
      canAnswer: false,
      maxRisk: 'LOW',
      dryRun: false,
    });
  });
  it('reads the limits from the settings', () => {
    expect(
      capabilities({
        settings: {
          allowedCommands: ['PauseWorkflowCommand'],
          canAnswer: true,
          maxRisk: 'medium',
        },
        dryRun: true,
      })
    ).toEqual({
      commands: ['PauseWorkflowCommand'],
      canAnswer: true,
      maxRisk: 'MEDIUM',
      dryRun: true,
    });
  });
  it('words a command', () => {
    expect(commandLabel('PauseWorkflowCommand')).toBe('pause workflow');
  });
});
