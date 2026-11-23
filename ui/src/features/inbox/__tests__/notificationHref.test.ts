// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { notificationHref, workflowHref } from '../notificationHref';

const base = {
  id: 'n 1',
  sessionId: 's/1',
  workflowId: 'wf',
  executionId: 'e1',
  nodeId: 'gate',
};

describe('notificationHref', () => {
  it('opens the run and its node, encoding every id', () => {
    expect(notificationHref(base)).toBe(
      '/sessions/s%2F1/workflow/wf?exec=e1&node=gate'
    );
  });

  it('drops the node when there is no run, and the run when there is none', () => {
    expect(notificationHref({ ...base, executionId: null })).toBe(
      '/sessions/s%2F1/workflow/wf'
    );
    expect(notificationHref({ ...base, nodeId: null })).toBe(
      '/sessions/s%2F1/workflow/wf?exec=e1'
    );
  });

  it('falls back to the session, then to the inbox row', () => {
    expect(notificationHref({ ...base, workflowId: null })).toBe(
      '/sessions/s%2F1'
    );
    expect(
      notificationHref({ ...base, sessionId: null, workflowId: null })
    ).toBe('/inbox?item=n 1');
    expect(workflowHref({ ...base, sessionId: null })).toBeNull();
  });
});
