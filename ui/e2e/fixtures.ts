// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test as base } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { SessionListItem } from '../src/features/sessions/types/session';

export interface TestFixtures {
  mockSessions: SessionListItem[];
  mockSessionsApi: (
    page: Page,
    response: { status?: number; sessions?: SessionListItem[] }
  ) => Promise<void>;
}

const defaultSessions: SessionListItem[] = [
  {
    sessionId: 'session-1',
    name: 'Build Pipeline',
    description: 'CI/CD workflow for main branch',
    initiator: 'github-actions',
    tags: ['production', 'automated'],
    projectPath: 'infenia/yukta',
    workflowCount: 3,
  },
  {
    sessionId: 'session-2',
    name: 'Code Review',
    description: 'Quality gate for PR #42',
    initiator: 'arun@infenia.com',
    tags: ['review', 'quality-gate'],
    projectPath: 'infenia/yukta',
    workflowCount: 5,
  },
  {
    sessionId: 'session-3',
    name: 'Data Pipeline',
    description: 'Nightly ETL job',
    initiator: 'scheduler',
    tags: ['nightly'],
    projectPath: 'infenia/data-platform',
    workflowCount: 2,
  },
];

async function mockSessionsApi(
  page: Page,
  { status = 200, sessions = defaultSessions }: { status?: number; sessions?: SessionListItem[] }
): Promise<void> {
  await page.route('**/api/sessions/summaries', (route) => {
    if (status !== 200) {
      return route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: new Date().toISOString(),
          status,
          message: 'Internal server error',
          path: '/api/sessions/summaries',
          error: 'Database connection failed',
        }),
      });
    }

    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        timestamp: new Date().toISOString(),
        status: 200,
        message: 'Sessions retrieved successfully',
        path: '/api/sessions/summaries',
        data: { sessions },
      }),
    });
  });
}

export const test = base.extend<TestFixtures>({
  mockSessions: async ({}, use) => {
    await use(defaultSessions);
  },
  mockSessionsApi: async ({}, use) => {
    await use(mockSessionsApi);
  },
  page: async ({ page }, use) => {
    // Every test gets the happy-path mock by default; individual tests can
    // call `mockSessionsApi(page, { ... })` again before navigating to
    // override it (e.g. empty list or a 500 response).
    await mockSessionsApi(page, {});
    await use(page);
  },
});

export { expect } from '@playwright/test';
