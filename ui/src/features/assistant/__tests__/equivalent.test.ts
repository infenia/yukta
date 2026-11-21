// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { cliEquivalent } from '../lib/equivalent';

const call = (name: string, args: Record<string, unknown>) => ({
  id: '1',
  name,
  args,
});

describe('cliEquivalent', () => {
  it('names the CLI command for a start', () => {
    expect(
      cliEquivalent(call('start_workflow', { sessionId: 's', workflowId: 'w' }))
    ).toContain('yukta-cli workflow start s w');
  });

  it('names the CLI command for a run control', () => {
    expect(
      cliEquivalent(
        call('control_workflow', {
          sessionId: 's',
          executionId: 'e',
          action: 'PAUSE',
        })
      )
    ).toContain('pause');
  });

  it.each([
    call('list_sessions', { sessionId: 's' }),
    call('start_workflow', {}),
    call('start_workflow', { sessionId: 's' }),
    call('control_workflow', {
      sessionId: 's',
      executionId: 'e',
      action: 'STOP_ALL',
    }),
  ])('has none for %j', (c) => {
    expect(cliEquivalent(c)).toBeNull();
  });
});
