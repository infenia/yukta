// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { directControl } from '../directControl';

describe('directControl', () => {
  it('owns the unowned workflows, is decided by people, and sits outside any hierarchy', () => {
    const view = directControl({
      unowned: 1,
      unownedWorkflows: [{ sessionId: 's', workflowId: 'w' }],
      conflicts: 0,
    });
    expect(view.owned).toEqual([{ sessionId: 's', workflowId: 'w' }]);
    expect(view.decisionMaker.name).toBe('People and agents');
    expect(view.parents).toEqual([]);
    expect(view.children).toEqual([]);
  });
});
