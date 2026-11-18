// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';
import { SupervisorEditorPage } from '../pages/SupervisorEditorPage';

// /supervisors/new and /supervisors/:id/edit: the form that creates or changes a supervisor.

const ENGINES = [
  { type: 'rules', displayName: 'Rules', category: 'RULES', icon: 'rule' },
  {
    type: 'http',
    displayName: 'External system',
    category: 'EXTERNAL_SYSTEM',
    icon: 'api',
  },
];

const supervisor = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  type: 'rules',
  description: 'Pauses the release when deploys keep failing.',
  settings: {},
  dryRun: false,
  system: false,
  attachments: 0,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
  parents: [],
  delegation: {},
  ...extra,
});

test.describe('Supervisor editor', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/supervisor-engines', (route) =>
      route.fulfill(envelope('/api/supervisor-engines', ENGINES))
    );
    await page.route('**/api/supervisors', (route) =>
      route.request().method() === 'GET'
        ? route.fulfill(
            envelope('/api/supervisors', [supervisor('release-guard')])
          )
        : route.fallback()
    );
  });

  test('creates a supervisor from the form', async ({ page }) => {
    let created: Record<string, unknown> | undefined;
    await page.route('**/api/supervisors', (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      created = route.request().postDataJSON();
      return route.fulfill(
        envelope('/api/supervisors', supervisor('new-one'), 201)
      );
    });
    const editor = new SupervisorEditorPage(page);

    await editor.gotoNew();
    await editor.decisionMaker.selectOption('rules');
    await editor.name.fill('new-one');
    await editor.description.fill('A new supervisor');
    await editor.button('Save').click();

    await expect.poll(() => created?.['id']).toBe('new-one');
    expect(created?.['type']).toBe('rules');
  });

  test('loads a supervisor to change it and saves the change', async ({
    page,
  }) => {
    await page.route('**/api/supervisors/release-guard', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill(
          envelope(
            '/api/supervisors/release-guard',
            supervisor('release-guard')
          )
        );
      }
      return route.fallback();
    });
    let saved: Record<string, unknown> | undefined;
    await page.route('**/api/supervisors/release-guard', (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      saved = route.request().postDataJSON();
      return route.fulfill(
        envelope('/api/supervisors/release-guard', supervisor('release-guard'))
      );
    });
    const editor = new SupervisorEditorPage(page);

    await editor.gotoEdit('release-guard');
    await expect(editor.description).toHaveValue(
      'Pauses the release when deploys keep failing.'
    );
    await editor.description.fill('Pauses a release that keeps failing.');
    await editor.button('Save').click();

    await expect
      .poll(() => saved?.['description'])
      .toBe('Pauses a release that keeps failing.');
  });
});
