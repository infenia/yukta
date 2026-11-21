// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { isRead } from '../lib/toolPolicy';

const tool = (name: string, readOnlyHint?: boolean) => ({
  name,
  description: '',
  inputSchema: {},
  annotations: readOnlyHint === undefined ? undefined : { readOnlyHint },
});

describe('isRead', () => {
  it.each([
    'list_sessions',
    'get_workflow_status',
    'diff_workflow_versions',
    'check_assignment',
    'read_agent_screen',
    'whoami',
  ])('treats %s as a read by its name', (name) =>
    expect(isRead(tool(name))).toBe(true)
  );

  it.each([
    'start_workflow',
    'delete_session',
    'control_node',
    'whoami_x',
    'listing',
  ])('asks before %s', (name) => expect(isRead(tool(name))).toBe(false));

  it('lets the tool say so', () => {
    expect(isRead(tool('start_workflow', true))).toBe(true);
    expect(isRead(tool('list_sessions', false))).toBe(false);
  });
});
