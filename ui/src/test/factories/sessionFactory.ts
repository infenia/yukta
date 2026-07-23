// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { SessionListItem } from '@/features/sessions/types/session';

export const createMockSession = (
  overrides?: Partial<SessionListItem>
): SessionListItem => ({
  sessionId: 'session-1',
  name: 'Test Session',
  description: 'A test session',
  initiator: 'user@example.com',
  tags: [],
  projectPath: '/path/to/project',
  workflowCount: 1,
  ...overrides,
});

export const createMockSessions = (
  count: number,
  overrides?: Partial<SessionListItem>
): SessionListItem[] =>
  Array.from({ length: count }, (_, i) =>
    createMockSession({
      sessionId: `session-${i + 1}`,
      name: `Session ${i + 1}`,
      description: `Description ${i + 1}`,
      initiator: `user${i + 1}@example.com`,
      workflowCount: i + 1,
      ...overrides,
    })
  );
