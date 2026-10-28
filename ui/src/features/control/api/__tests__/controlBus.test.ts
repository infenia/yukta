// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { parseBusNode } from '../controlBus';

describe('parseBusNode', () => {
  it('splits at the first colon only', () => {
    expect(parseBusNode('wf:a:b')).toEqual({ workflowId: 'wf', nodeId: 'a:b' });
  });

  it('keeps an entry without a colon as a workflow with no node', () => {
    expect(parseBusNode('solo')).toEqual({ workflowId: 'solo', nodeId: '' });
  });
});
