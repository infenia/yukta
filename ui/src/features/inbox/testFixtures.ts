// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { InputRequest } from '@/features/workflow/api/inputs';
import type { AppNotification } from './api';

export const input = (over: Partial<InputRequest> = {}): InputRequest => ({
  id: 'r1',
  sessionId: 's1',
  workflowId: 'wf',
  executionId: 'e1',
  nodeId: 'gate',
  state: 'OPEN',
  version: 3,
  kind: 'APPROVAL',
  prompt: 'Deploy?',
  context: {},
  options: [],
  responseSchema: null,
  risk: 'LOW',
  tier: 'operator',
  advice: null,
  deadline: null,
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
  answer: null,
  ...over,
});

let seq = 0;

export const note = (over: Partial<AppNotification> = {}): AppNotification => ({
  id: `n${++seq}`,
  key: 'k',
  kind: 'INFO',
  severity: 'INFO',
  category: 'run.failed',
  title: 'Run failed',
  body: null,
  sessionId: 's1',
  workflowId: 'wf',
  executionId: 'e1',
  nodeId: 'gate',
  inputRequestId: null,
  state: 'ACTIVE',
  resolution: null,
  count: 1,
  seq,
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
  read: false,
  dismissed: false,
  input: null,
  ...over,
});

export const action = (over: Partial<AppNotification> = {}) =>
  note({
    kind: 'ACTION',
    category: 'input',
    severity: 'WARN',
    title: 'Deploy?',
    inputRequestId: 'r1',
    input: input(),
    ...over,
  });
