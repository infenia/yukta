// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';
import { SessionDetailPage } from '../pages/SessionDetailPage';

// /sessions/:id/edit: the form of an existing session. Saving sends the whole session back, so the
// workflows the form does not show must survive.

const config = {
  sessionId: 'session-1',
  name: 'Build Pipeline',
  description: 'Compiles and tests the app',
  initiator: 'ci',
  tags: { team: 'core' },
  projectPath: '/srv/repos/app',
  workflows: {
    build: {
      workflowId: 'build',
      description: 'Build it',
      nodes: [
        { nodeId: 'start', type: 'CONSTANT_SOURCE', config: {} },
        { nodeId: 'end', type: 'CONSOLE_TERMINAL', config: {} },
      ],
      edges: [{ source: 'start', target: 'end' }],
    },
  },
};

test.describe('Edit session', () => {
  test('loads the session into the form and saves what changed with its workflows', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, {});
    await page.route('**/api/sessions/session-1/config', (route) =>
      route.fulfill(envelope('/api/sessions/session-1/config', config))
    );
    await page.route('**/api/sessions/validate/project-path', (route) =>
      route.fulfill(
        envelope('/project-path', { exists: true, directory: true })
      )
    );
    let saved: typeof config | undefined;
    await page.route('**/api/sessions', (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      saved = route.request().postDataJSON();
      return route.fulfill(envelope('/api/sessions', null));
    });

    const session = new SessionDetailPage(page);
    await session.gotoEdit('session-1');

    await expect(session.field('Name')).toHaveValue('Build Pipeline');
    await expect(session.field('Working directory')).toHaveValue(
      '/srv/repos/app'
    );
    await session.field('Name').fill('Build and release');
    await session.save.click();

    await expect(page).toHaveURL(/\/sessions\/session-1/);
    // the edit page's own URL matches too, so wait for the POST itself
    await expect.poll(() => saved?.name).toBe('Build and release');
    expect(saved?.sessionId).toBe('session-1');
    expect(saved?.workflows.build.nodes).toHaveLength(2);
  });

  test('says so when the session cannot be loaded', async ({ page }) => {
    await page.route('**/api/sessions/gone/config', (route) =>
      route.fulfill(envelope('/api/sessions/gone/config', null, 404))
    );

    await new SessionDetailPage(page).gotoEdit('gone');

    await expect(page.getByText('Failed to load the session')).toBeVisible();
  });
});
