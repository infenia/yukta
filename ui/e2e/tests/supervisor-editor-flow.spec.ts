// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { choose, expectOptions } from '../utils';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

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
  settings: { rules: [] },
  dryRun: false,
  system: false,
  attachments: 0,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
  parents: ['release-lead', 'audit'],
  delegation: {},
  ...extra,
});

interface Calls {
  creates: Record<string, unknown>[];
  replaces: Record<string, unknown>[];
}

async function mockEditor(
  page: Page,
  {
    saveStatus = 200,
    saveDelay = 0,
  }: { saveStatus?: number; saveDelay?: number } = {}
): Promise<Calls> {
  const calls: Calls = { creates: [], replaces: [] };
  await page.route('**/api/supervisor-engines', (r) =>
    r.fulfill(envelope('/engines', ENGINES))
  );
  await page.route('**/api/supervisors', async (r) => {
    if (r.request().method() === 'POST') {
      calls.creates.push(r.request().postDataJSON());
      if (saveDelay)
        await new Promise((resolve) => setTimeout(resolve, saveDelay));
      return saveStatus === 200
        ? r.fulfill(envelope('/supervisors', supervisor('new-one'), 201))
        : r.fulfill({
            status: saveStatus,
            contentType: 'application/json',
            body: JSON.stringify({
              message: 'Validation failed',
              errors: [{ field: 'parents', message: 'would make a cycle' }],
            }),
          });
    }
    return r.fulfill(envelope('/supervisors', [supervisor('release-guard')]));
  });
  await page.route('**/api/supervisors/release-guard', (r) => {
    if (r.request().method() === 'PUT') {
      calls.replaces.push(r.request().postDataJSON());
      return saveStatus === 200
        ? r.fulfill(
            envelope('/supervisors/release-guard', supervisor('release-guard'))
          )
        : r.fulfill({
            status: saveStatus,
            contentType: 'application/json',
            body: '{"message":"The supervisor changed meanwhile"}',
          });
    }
    return r.fulfill(
      envelope('/supervisors/release-guard', supervisor('release-guard'))
    );
  });
  return calls;
}

const name = (page: Page) => page.getByLabel('Name', { exact: true });
const save = (page: Page) => page.getByRole('button', { name: /^Sav/ });

test.describe('Supervisor editor - a new supervisor', () => {
  test.beforeEach(async ({ page }) => {
    await mockEditor(page);
  });

  test('has a title, an explanation, a Back link and an agent equivalent', async ({
    page,
  }) => {
    await page.goto('/supervisors/new');

    await expect(
      page.getByRole('heading', { level: 1, name: 'New supervisor' })
    ).toBeVisible();
    await expect(page.getByText('For a quick change by hand.')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Back' })).toHaveAttribute(
      'href',
      '/supervisors'
    );
    await expect(page.getByText('save_supervisor {"id": "<id>"')).toBeVisible();
    await expect(page.getByText('yukta supervisors show <id>')).toBeVisible();
  });

  test('starts in dry run with empty settings and offers every decision maker', async ({
    page,
  }) => {
    await page.goto('/supervisors/new');

    await expect(name(page)).toBeEnabled();
    await expect(page.getByLabel('Decision maker')).toHaveAttribute(
      'data-value',
      ''
    );
    await expectOptions(page.getByLabel('Decision maker'), [
      'Choose…',
      'Rules',
      'External system',
    ]);
    await expect(
      page.getByRole('checkbox', {
        name: /Dry run: record its commands, apply none/,
      })
    ).toBeChecked();
    await expect(page.getByLabel('Settings (JSON)')).toHaveValue('{}');
    await expect(
      page.getByText('Changing it replaces the settings below.')
    ).toHaveCount(0);
    await expect(
      page.getByText('A cycle is refused when it is saved.')
    ).toBeVisible();
  });

  test('Save waits for a valid name and a decision maker', async ({ page }) => {
    await page.goto('/supervisors/new');
    await expect(save(page)).toBeDisabled();

    await name(page).fill('good-name');
    await expect(save(page)).toBeDisabled();

    await choose(page.getByLabel('Decision maker'), 'rules');
    await expect(save(page)).toBeEnabled();
  });

  test('a name with other characters is flagged and blocks Save', async ({
    page,
  }) => {
    await page.goto('/supervisors/new');
    await choose(page.getByLabel('Decision maker'), 'rules');

    await name(page).fill('not valid!');

    await expect(
      page.getByText('Use letters, digits, dot, dash or underscore, up to 64')
    ).toBeVisible();
    await expect(name(page)).toHaveAttribute('aria-invalid', 'true');
    await expect(save(page)).toBeDisabled();

    await name(page).fill('valid.name_1-ok');
    await expect(name(page)).toHaveAttribute('aria-invalid', 'false');
    await expect(save(page)).toBeEnabled();
  });

  test('a name longer than 64 characters is refused', async ({ page }) => {
    await page.goto('/supervisors/new');
    await choose(page.getByLabel('Decision maker'), 'rules');

    await name(page).fill('a'.repeat(65));

    await expect(save(page)).toBeDisabled();
    await name(page).fill('a'.repeat(64));
    await expect(save(page)).toBeEnabled();
  });

  test('settings that are not valid JSON, or not an object, are explained and block Save', async ({
    page,
  }) => {
    await page.goto('/supervisors/new');
    await name(page).fill('s1');
    await choose(page.getByLabel('Decision maker'), 'rules');
    const settings = page.getByLabel('Settings (JSON)');

    await settings.fill('{nope');
    await expect(page.getByText(/Settings are not valid JSON/)).toBeVisible();
    await expect(settings).toHaveAttribute('aria-invalid', 'true');
    await expect(save(page)).toBeDisabled();

    await settings.fill('[1, 2]');
    await expect(
      page.getByText('Settings must be a JSON object')
    ).toBeVisible();
    await expect(save(page)).toBeDisabled();

    await settings.fill('');
    await expect(
      page
        .getByText(/Settings/, { exact: false })
        .filter({ hasText: 'must be' })
    ).toHaveCount(0);
    await expect(save(page)).toBeEnabled();
  });

  test('creates it with every field, reports to split into a list, and opens it', async ({
    page,
  }) => {
    const calls = await mockEditor(page);
    await page.goto('/supervisors/new');

    await name(page).fill('new-one');
    await page.getByLabel('Description').fill('A new supervisor');
    await choose(page.getByLabel('Decision maker'), 'http');
    await page.getByRole('checkbox', { name: /Dry run/ }).uncheck();
    await page.getByLabel('Reports to').fill(' release-lead , audit,, ');
    await page
      .getByLabel('Settings (JSON)')
      .fill('{"url": "https://agent.example.com"}');
    await save(page).click();

    await expect(page).toHaveURL(/\/supervisors\?supervisor=new-one$/);
    expect(calls.creates).toEqual([
      {
        id: 'new-one',
        type: 'http',
        description: 'A new supervisor',
        dryRun: false,
        parents: ['release-lead', 'audit'],
        settings: { url: 'https://agent.example.com' },
      },
    ]);
  });

  test('an empty description is left out', async ({ page }) => {
    const calls = await mockEditor(page);
    await page.goto('/supervisors/new');
    await name(page).fill('bare');
    await choose(page.getByLabel('Decision maker'), 'rules');

    await save(page).click();

    await expect(page).toHaveURL(/supervisor=bare/);
    expect(calls.creates[0]).not.toHaveProperty('description');
    expect(calls.creates[0]).toMatchObject({
      dryRun: true,
      parents: [],
      settings: {},
    });
  });

  test('a refusal is shown with its fields and the form can be sent again', async ({
    page,
  }) => {
    await mockEditor(page, { saveStatus: 409 });
    await page.goto('/supervisors/new');
    await name(page).fill('cyclic');
    await choose(page.getByLabel('Decision maker'), 'rules');

    await save(page).click();

    const alert = page.getByRole('alert');
    await expect(alert).toContainText('Validation failed');
    await expect(alert).toContainText('parents: would make a cycle');
    await expect(save(page)).toBeEnabled();
    await expect(page).toHaveURL(/\/supervisors\/new$/);
  });

  test('shows Saving… while the request is out', async ({ page }) => {
    await mockEditor(page, { saveDelay: 800 });
    await page.goto('/supervisors/new');
    await name(page).fill('slow');
    await choose(page.getByLabel('Decision maker'), 'rules');

    await page.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    await expect(page).toHaveURL(/supervisor=slow/);
  });

  test('Back returns to the supervisors', async ({ page }) => {
    await page.goto('/supervisors/new');

    await page.getByRole('link', { name: 'Back' }).click();

    await expect(page).toHaveURL(/\/supervisors$/);
  });
});

test.describe('Supervisor editor - an existing supervisor', () => {
  test('loads every field of the supervisor and fixes its name', async ({
    page,
  }) => {
    await mockEditor(page);
    await page.goto('/supervisors/release-guard/edit');

    await expect(
      page.getByRole('heading', { level: 1, name: 'Edit release-guard' })
    ).toBeVisible();
    await expect(name(page)).toHaveValue('release-guard');
    await expect(name(page)).toBeDisabled();
    await expect(page.getByLabel('Description')).toHaveValue(
      'Pauses the release when deploys keep failing.'
    );
    await expect(page.getByLabel('Decision maker')).toHaveAttribute(
      'data-value',
      'rules'
    );
    await expect(
      page.getByRole('checkbox', { name: /Dry run/ })
    ).not.toBeChecked();
    await expect(page.getByLabel('Reports to')).toHaveValue(
      'release-lead, audit'
    );
    await expect(page.getByLabel('Settings (JSON)')).toHaveValue(
      JSON.stringify({ rules: [] }, null, 2)
    );
    await expect(
      page.getByText('Changing it replaces the settings below.')
    ).toBeVisible();
    await expect(
      page.getByText('yukta supervisors show release-guard')
    ).toBeVisible();
  });

  test('Save replaces the supervisor with the changes and opens it', async ({
    page,
  }) => {
    const calls = await mockEditor(page);
    await page.goto('/supervisors/release-guard/edit');

    await page.getByLabel('Description').fill('Changed by hand');
    await page.getByRole('checkbox', { name: /Dry run/ }).check();
    await page.getByLabel('Reports to').fill('');
    await save(page).click();

    await expect(page).toHaveURL(/\/supervisors\?supervisor=release-guard$/);
    expect(calls.replaces).toEqual([
      {
        id: 'release-guard',
        type: 'rules',
        description: 'Changed by hand',
        dryRun: true,
        parents: [],
        settings: { rules: [] },
      },
    ]);
    expect(calls.creates).toHaveLength(0);
  });

  test('a refusal on save is shown and nothing is opened', async ({ page }) => {
    await mockEditor(page, { saveStatus: 409 });
    await page.goto('/supervisors/release-guard/edit');

    await save(page).click();

    await expect(page.getByRole('alert')).toContainText(
      'The supervisor changed meanwhile'
    );
    await expect(page).toHaveURL(/\/supervisors\/release-guard\/edit$/);
  });

  test('a supervisor that cannot be loaded says which one and shows no form', async ({
    page,
  }) => {
    await mockEditor(page);
    await page.route('**/api/supervisors/gone', (r) =>
      r.fulfill({
        status: 404,
        contentType: 'application/json',
        body: '{"message":"none"}',
      })
    );
    await page.goto('/supervisors/gone/edit');

    await expect(page.getByText('Failed to load gone.')).toBeVisible();
    await expect(page.getByLabel('Settings (JSON)')).toHaveCount(0);
  });

  test('shows a loading state while it loads', async ({ page }) => {
    await mockEditor(page);
    await page.route('**/api/supervisors/release-guard', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      return route.fulfill(envelope('/s', supervisor('release-guard')));
    });
    await page.goto('/supervisors/release-guard/edit');

    await expect(page.getByText('Loading…')).toBeVisible();
    await expect(name(page)).toHaveValue('release-guard');
  });
});

test.describe('Supervisor editor - permissions and the drawer', () => {
  test('a VIEWER key cannot save', async ({ page }) => {
    await mockEditor(page);
    await page.route('**/api/me', (r) =>
      r.fulfill(
        envelope('/api/me', {
          authenticated: true,
          keyName: 'reader',
          role: 'VIEWER',
          sessions: null,
          authEnabled: true,
          permissions: {
            startRuns: false,
            controlRuns: false,
            editSessions: false,
            deleteSessions: false,
            changeLimits: false,
            sendCommands: false,
          },
        })
      )
    );
    await page.goto('/supervisors/new');
    await name(page).fill('x');
    await choose(page.getByLabel('Decision maker'), 'rules');

    await expect(page.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  test('Create new in a session form opens the same form in a drawer, in dry run', async ({
    page,
  }) => {
    await mockEditor(page);
    await page.route('**/api/templates', (r) =>
      r.fulfill(envelope('/templates', []))
    );
    await page.goto('/sessions/new');
    await page.getByRole('button', { name: 'Start blank' }).click();

    await page.getByRole('button', { name: 'Create new' }).click();

    await expect(page.getByLabel('Decision maker')).toBeVisible();
    await expect(page.getByRole('checkbox', { name: /Dry run/ })).toBeChecked();
  });
});
