// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';

const envelope = (path: string, data: unknown, status = 200) => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({
    timestamp: new Date().toISOString(),
    status,
    message: 'OK',
    path,
    data,
  }),
});

const type = {
  type: 'failure-guard',
  displayName: 'Failure guard',
  description: 'Pauses a workflow after repeated failures',
  usagePattern: '',
  category: 'GUARD',
  kind: 'SUPERVISOR',
  icon: null,
  configSchema: [],
  commonSchema: [],
  defaultAllowedCommands: null,
  exampleSettings: {},
};

/** Stateful mocks of the whole flow: the supervisor made in the wizard shows up everywhere after. */
async function mockSupervisorsApi(page: Page) {
  const state = {
    supervisors: [] as { id: string; type: string; system: boolean }[],
    savedSession: null as null | {
      workflows: Record<string, { assignments?: unknown[] }>;
    },
  };
  await page.route('**/api/supervisor-types', (route) =>
    route.fulfill(envelope('/api/supervisor-types', [type]))
  );
  await page.route('**/api/supervisor-types/*', (route) =>
    route.fulfill(envelope('/api/supervisor-types/failure-guard', type))
  );
  await page.route('**/api/supervisors/validate', (route) =>
    route.fulfill(envelope('/validate', { valid: true, errors: [] }))
  );
  await page.route('**/api/supervisors', async (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON();
      state.supervisors.push({ id: body.id, type: body.type, system: false });
      return route.fulfill(envelope('/api/supervisors', body, 201));
    }
    return route.fulfill(envelope('/api/supervisors', state.supervisors));
  });
  await page.route('**/api/supervisors/attachments*', (route) =>
    route.fulfill(
      envelope(
        '/attachments',
        state.savedSession
          ? [
              {
                id: 'def-1',
                supervisorId: 'fresh',
                scope: 'workflow:session-1/build',
                enabled: true,
                source: 'DEFINITION',
                originSessionId: 'session-1',
                originWorkflowId: 'build',
              },
            ]
          : []
      )
    )
  );
  await page.route('**/api/supervisors/decisions*', (route) =>
    route.fulfill(
      envelope('/decisions', [
        {
          time: new Date().toISOString(),
          supervisorId: 'fresh',
          outcome: 'applied',
          command: 'PauseWorkflowCommand',
          executionId: 'run-1',
          reason: null,
        },
      ])
    )
  );
  await page.route('**/api/supervisors/status', (route) =>
    route.fulfill(envelope('/status', []))
  );
  await page.route('**/api/plugins', (route) =>
    route.fulfill(envelope('/api/plugins', []))
  );
  await page.route('**/api/sessions/session-1/config', (route) =>
    route.fulfill(
      envelope('/config', {
        sessionId: 'session-1',
        name: 'Build Pipeline',
        description: '',
        initiator: 'ci',
        tags: {},
        projectPath: 'infenia/yukta',
        workflows: {},
      })
    )
  );
  await page.route('**/api/sessions/validate', (route) =>
    route.fulfill(
      envelope('/validate', { valid: true, message: null, outputPorts: {} })
    )
  );
  await page.route('**/api/sessions', (route) => {
    if (route.request().method() === 'POST') {
      state.savedSession = route.request().postDataJSON();
    }
    return route.fulfill(envelope('/api/sessions', null));
  });
  return state;
}

test.describe('Supervisors end to end', () => {
  test('creates a supervisor, declares it in a new workflow and sees its decisions', async ({
    page,
    mockWorkflowApi,
  }) => {
    const state = await mockSupervisorsApi(page);
    await mockWorkflowApi(page);

    await page.goto('/supervisors/new');
    await page.getByLabel('Supervisor type').selectOption('failure-guard');
    await page.getByRole('button', { name: 'Next' }).click();
    await page.getByLabel('Name').fill('fresh');
    await page.getByRole('button', { name: 'Next' }).click();
    await page.getByRole('button', { name: 'Next' }).click();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect
      .poll(() => state.supervisors.map((s) => s.id))
      .toEqual(['fresh']);

    await page.goto('/sessions/session-1/workflow/build/edit');
    await page.getByText(/Supervisors \(0\)/).click();
    await page.getByRole('button', { name: 'Add supervisor' }).click();
    await expect(page.getByLabel('Supervisor 1', { exact: true })).toHaveValue(
      'fresh'
    );
    await page.getByRole('button', { name: 'Save version' }).click();
    await expect
      .poll(() => state.savedSession?.workflows.build.assignments)
      .toEqual([{ supervisor: 'fresh' }]);

    await page.goto('/sessions/session-1/workflow/build');
    const effective = page.getByRole('region', { name: 'Supervisors' });
    await expect(effective).toContainText('fresh');
    await expect(effective).toContainText('Definition');

    await page.goto('/supervisors');
    await page.getByRole('tab', { name: 'Activity' }).click();
    await expect(page.getByText('PauseWorkflowCommand')).toBeVisible();
  });
});
