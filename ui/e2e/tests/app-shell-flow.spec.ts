// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

test.describe('App shell - routes and redirects', () => {
  test('/ opens the session list and keeps its query', async ({ page }) => {
    await page.goto('/?q=build');

    await expect(page).toHaveURL(/\/sessions\?q=build$/);
    await expect(
      page.getByRole('heading', { level: 1, name: 'Sessions' })
    ).toBeVisible();
  });

  test('an old /history link lands on the Runs page with its query', async ({
    page,
  }) => {
    await page.goto('/history?session=build&status=FAILURE');

    await expect(page).toHaveURL(/\/runs\?session=build&status=FAILURE$/);
    await expect(
      page.getByRole('heading', { level: 1, name: 'Runs' })
    ).toBeVisible();
  });

  test('a supervisor page address selects that supervisor in the list', async ({
    page,
  }) => {
    await page.goto('/supervisors/release-guard');

    await expect(page).toHaveURL(/\/supervisors\?supervisor=release-guard$/);
    await expect(
      page.getByRole('heading', { level: 1, name: 'Supervisors' })
    ).toBeVisible();
  });

  test('an address that matches no page shows the router 404 page', async ({
    page,
  }) => {
    await page.goto('/no/such/page');

    await expect(page).toHaveURL(/\/no\/such\/page$/);
    await expect(page.getByText(/404|Not Found/i).first()).toBeVisible();
  });

  test('the dashboard showcase renders its sample panels', async ({ page }) => {
    await page.goto('/showcase/dashboard');

    const showcase = page.getByTestId('dashboard-showcase');
    await expect(showcase).toBeVisible();
    await expect(
      page.getByRole('heading', { level: 1, name: 'Dashboard components' })
    ).toBeVisible();
    await expect(showcase).toContainText('Throughput / min');
    await expect(showcase).toContainText('Pending approvals');
    await expect(showcase).toContainText('Capacity');
  });
});

test.describe('App shell - the footer', () => {
  test('shows the copyright on the list pages', async ({ page }) => {
    for (const url of ['/sessions', '/plugins', '/runs']) {
      await page.goto(url);
      await expect(page.getByRole('contentinfo')).toContainText(
        '© 2026 Infenia Private Limited. All rights reserved.'
      );
    }
  });

  test('is left out of a run view, which fills the screen', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);

    await page.goto('/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11');
    await expect(page.getByTestId('workflow-details-page')).toBeVisible();

    await expect(page.getByRole('contentinfo')).toHaveCount(0);
  });

  test('comes back on the run view editor, which scrolls', async ({ page }) => {
    await page.route('**/api/sessions/session-1/config', (r) =>
      r.fulfill(
        envelope('/config', {
          sessionId: 'session-1',
          name: 'S',
          description: '',
          initiator: 'ci',
          tags: {},
          projectPath: '/p',
          workflows: {},
        })
      )
    );
    await page.goto('/sessions/session-1/workflow/build/edit');
    await expect(page.getByTestId('workflow-editor')).toBeVisible();

    await expect(page.getByRole('contentinfo')).toBeVisible();
  });
});

test.describe('App shell - breadcrumbs', () => {
  test('a top-level page has none', async ({ page }) => {
    await page.goto('/runs');

    await expect(
      page.getByRole('navigation', { name: /breadcrumb/i })
    ).toHaveCount(0);
  });

  test('a session page shows Sessions › the session, with the first as a link', async ({
    page,
  }) => {
    await page.goto('/sessions/dogfood');

    const trail = page.getByRole('navigation', { name: /breadcrumb/i });
    await expect(trail).toBeVisible();
    await expect(trail.getByRole('link', { name: 'Sessions' })).toHaveAttribute(
      'href',
      '/sessions'
    );
    await expect(trail.getByText('dogfood')).toHaveAttribute(
      'aria-current',
      'page'
    );
  });

  test('a workflow page shows Sessions › session › workflow, each earlier crumb linking back', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await page.goto('/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11');

    const trail = page.getByRole('navigation', { name: /breadcrumb/i });
    await expect(trail.getByRole('link')).toHaveText([
      'Sessions',
      'SESSION_A92_XP_2024',
    ]);
    await expect(trail.getByText('wf-982-xk-11')).toHaveAttribute(
      'aria-current',
      'page'
    );

    await trail.getByRole('link', { name: 'SESSION_A92_XP_2024' }).click();
    await expect(page).toHaveURL(/\/sessions\/SESSION_A92_XP_2024$/);
  });

  test('the new and import pages show their trail', async ({ page }) => {
    await page.route('**/api/templates', (r) =>
      r.fulfill(envelope('/templates', []))
    );

    await page.goto('/sessions/import');
    await expect(
      page.getByRole('navigation', { name: /breadcrumb/i }).getByText('Import')
    ).toBeVisible();
  });
});

test.describe('App shell - the page transitions and the layout', () => {
  test('moving between pages keeps the shell mounted', async ({ page }) => {
    await page.goto('/sessions');
    await page.evaluate(() => {
      (window as unknown as { shell: boolean }).shell = true;
    });

    await page.getByRole('link', { name: 'Plugins' }).first().click();
    await expect(
      page.getByRole('heading', { level: 1, name: 'Plugins' })
    ).toBeVisible();
    await page.getByRole('link', { name: 'Runs' }).first().click();
    await expect(
      page.getByRole('heading', { level: 1, name: 'Runs' })
    ).toBeVisible();

    expect(
      await page.evaluate(
        () => (window as unknown as { shell?: boolean }).shell
      )
    ).toBe(true);
  });

  test('every page has exactly one h1', async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page);
    await page.route('**/api/templates', (r) =>
      r.fulfill(envelope('/templates', []))
    );
    for (const url of [
      '/sessions',
      '/runs',
      '/plugins',
      '/supervisors',
      '/control',
      '/inbox',
      '/sessions/new',
      '/sessions/import',
    ]) {
      await page.goto(url);
      await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
    }
  });

  test('no page scrolls sideways on a phone', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    for (const url of [
      '/sessions',
      '/runs',
      '/plugins',
      '/supervisors',
      '/control',
      '/inbox',
    ]) {
      await page.goto(url);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      const overflow = await page.evaluate(
        () =>
          document.documentElement.scrollWidth -
          document.documentElement.clientWidth
      );
      expect(overflow, url).toBeLessThanOrEqual(0);
    }
  });
});
