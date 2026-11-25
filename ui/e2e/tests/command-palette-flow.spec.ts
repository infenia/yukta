// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect, defaultProgress } from '../fixtures';
import { envelope } from '../api-envelope';
import { mockSupervisorDetail } from '../supervisor-detail';

const session = (id: string, name: string) => ({
  sessionId: id,
  name,
  description: '',
  initiator: 'ci',
  tags: [],
  projectPath: '/p',
  workflowCount: 1,
});

const SESSIONS = [
  session('build', 'Build Pipeline'),
  session('deploy', 'Deploy'),
];

const supervisor = (id: string, description: string | null) => ({
  id,
  type: 'rules',
  description,
  settings: {},
  dryRun: false,
  system: false,
  attachments: 0,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
});

interface Mock {
  /** The run searches the palette made. */
  searches: URL[];
}

async function mockPalette(
  page: Page,
  { sessions = SESSIONS }: { sessions?: ReturnType<typeof session>[] } = {}
): Promise<Mock> {
  const searches: URL[] = [];
  await page.route('**/api/sessions/summaries*', (r) =>
    r.fulfill(envelope('/s', sessions))
  );
  await page.route(/\/api\/sessions\/[^/]+\/workflows$/, (r) => {
    const id = new URL(r.request().url()).pathname.split('/')[3];
    return r.fulfill(
      envelope('/w', [
        {
          workflowId: `${id}-flow`,
          description: '',
          nodeCount: 7,
          edgeCount: 6,
          status: 'SUCCESS',
        },
      ])
    );
  });
  await page.route('**/api/templates', (r) =>
    r.fulfill(envelope('/templates', []))
  );
  await page.route('**/api/supervisors', (r) =>
    r.fulfill(
      envelope('/supervisors', [
        supervisor('release-guard', 'Pauses bad releases'),
        supervisor('audit-bot', null),
      ])
    )
  );
  await page.route('**/api/executions?*', (r) => {
    const url = new URL(r.request().url());
    searches.push(url);
    return r.fulfill(
      envelope('/executions', [
        {
          sessionId: 'build',
          executionId: 'abc12345-0000-1111-2222-333344445555',
          workflowId: 'build-flow',
          status: 'FAILURE',
          startTime: '2026-07-26T14:00:00',
          endTime: '2026-07-26T14:01:00',
        },
      ])
    );
  });
  return { searches };
}

const palette = (page: Page) =>
  page.getByRole('dialog', { name: 'Command palette' });
const search = (page: Page) => palette(page).getByRole('combobox');
const option = (page: Page, text: string | RegExp) =>
  palette(page).getByRole('option').filter({ hasText: text });

/** Opens the palette from the header button, which is there once the app has mounted. */
async function open(page: Page, url = '/sessions') {
  await page.goto(url);
  await page.getByRole('button', { name: /Search and jump to/ }).click();
  await expect(palette(page)).toBeVisible();
}

test.describe('Command palette - opening and closing', () => {
  test.beforeEach(async ({ page }) => {
    await mockPalette(page);
  });

  test('opens with Ctrl+K and focuses the search box, with a hint line', async ({
    page,
  }) => {
    await open(page);

    await expect(search(page)).toBeFocused();
    await expect(search(page)).toHaveAttribute(
      'placeholder',
      'Search sessions, workflows, runs and actions'
    );
    await expect(palette(page)).toContainText('↑ ↓ move');
    await expect(palette(page)).toContainText('⏎ open');
    await expect(palette(page)).toContainText('Esc close');
  });

  test('Ctrl+K opens it once the page has loaded', async ({ page }) => {
    await page.goto('/sessions');
    await expect(
      page.getByRole('button', { name: /Search and jump to/ })
    ).toBeVisible();

    await page.keyboard.press('Control+k');

    await expect(palette(page)).toBeVisible();
  });

  test('Ctrl+K again closes it, and so do Escape and a click outside', async ({
    page,
  }) => {
    await open(page);
    await page.keyboard.press('Control+k');
    await expect(palette(page)).toBeHidden();

    await page.keyboard.press('Control+k');
    await expect(palette(page)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(palette(page)).toBeHidden();

    await page.keyboard.press('Control+k');
    await expect(palette(page)).toBeVisible();
    await page.mouse.click(5, 5);
    await expect(palette(page)).toBeHidden();
  });

  test('works on every page, for instance the Runs page', async ({ page }) => {
    await open(page, '/runs');

    await expect(palette(page)).toBeVisible();
  });
});

test.describe('Command palette - what it lists', () => {
  test.beforeEach(async ({ page }) => {
    await mockPalette(page);
    await open(page);
  });

  test('lists the pages, supervisors, sessions, workflows and actions in groups', async ({
    page,
  }) => {
    for (const group of [
      'Pages',
      'Supervisors',
      'Sessions',
      'Workflows',
      'Actions',
    ]) {
      await expect(
        palette(page).getByRole('group', { name: group })
      ).toBeVisible();
    }
    await expect(
      palette(page).getByRole('group', { name: 'Pages' }).getByRole('option')
    ).toHaveText([/Sessions/, /Runs/, /Plugins/, /Supervisors/, /Dashboard/]);
    await expect(option(page, 'Go to supervisor release-guard')).toContainText(
      'Pauses bad releases'
    );
    await expect(option(page, 'Build Pipeline')).toContainText('build');
    await expect(option(page, 'build-flow')).toContainText(
      'in build · 7 nodes'
    );
    await expect(
      palette(page).getByRole('group', { name: 'Actions' }).getByRole('option')
    ).toHaveText([
      /New session/,
      /Ask the assistant/,
      /Open inbox/,
      /dark theme/,
      /Copy link to this page/,
    ]);
  });

  test('typing narrows every group, and says when nothing matches', async ({
    page,
  }) => {
    await search(page).fill('deploy');

    await expect(option(page, 'Deploy').first()).toBeVisible();
    await expect(option(page, 'Build Pipeline')).toHaveCount(0);
    await expect(
      palette(page).getByRole('group', { name: 'Pages' })
    ).toHaveCount(0);

    await search(page).fill('zz');
    await expect(
      palette(page).getByText('Nothing matches “zz”.')
    ).toBeVisible();
    await expect(palette(page).getByRole('option')).toHaveCount(0);
  });

  test('the first match is selected and the arrow keys move and wrap', async ({
    page,
  }) => {
    const options = palette(page).getByRole('option');
    await expect(options.first()).toHaveAttribute('aria-selected', 'true');

    await page.keyboard.press('ArrowDown');
    await expect(options.nth(1)).toHaveAttribute('aria-selected', 'true');
    await expect(options.first()).toHaveAttribute('aria-selected', 'false');

    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowUp');
    await expect(options.last()).toHaveAttribute('aria-selected', 'true');
    await expect(search(page)).toHaveAttribute('aria-activedescendant', /.+/);
  });

  test('shows at most five results per group', async ({ page }) => {
    await mockPalette(page, {
      sessions: Array.from({ length: 8 }, (_, i) =>
        session(`s${i}`, `Session ${i}`)
      ),
    });
    await page.reload();
    await page.getByRole('button', { name: /Search and jump to/ }).click();

    await expect(
      palette(page).getByRole('group', { name: 'Sessions' }).getByRole('option')
    ).toHaveCount(5);
  });
});

test.describe('Command palette - doing things', () => {
  test.beforeEach(async ({ page }) => {
    await mockPalette(page);
  });

  test('Enter opens the selected page', async ({ page }) => {
    await open(page);
    await search(page).fill('runs');
    await expect(option(page, 'Runs').first()).toHaveAttribute(
      'aria-selected',
      'true'
    );

    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/runs$/);
    await expect(palette(page)).toBeHidden();
  });

  test('clicking a session, a supervisor and a workflow opens each', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await mockSupervisorDetail(page);
    await open(page);
    await option(page, 'Build Pipeline').click();
    await expect(page).toHaveURL(/\/sessions\/build$/);

    await page.keyboard.press('Control+k');
    await option(page, 'Go to supervisor release-guard').click();
    await expect(page).toHaveURL(/\/supervisors\?supervisor=release-guard$/);

    await page.keyboard.press('Control+k');
    await option(page, 'deploy-flow').click();
    await expect(page).toHaveURL(/\/sessions\/deploy\/workflow\/deploy-flow$/);
  });

  test('the actions open a new session and the inbox', async ({ page }) => {
    await open(page);
    await option(page, 'New session').click();
    await expect(page).toHaveURL(/\/sessions\/new$/);

    await page.keyboard.press('Control+k');
    await option(page, 'Open inbox').click();
    await expect(page).toHaveURL(/\/inbox$/);
  });

  test('the theme action switches the theme and its label follows', async ({
    page,
  }) => {
    await open(page);
    await option(page, 'Switch to dark theme').click();
    await expect(page.locator('html')).toHaveClass(/dark/);

    await page.keyboard.press('Control+k');
    await expect(option(page, 'Switch to light theme')).toBeVisible();
    await option(page, 'Switch to light theme').click();
    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });

  test('Copy link copies this page address and closes the palette', async ({
    page,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await open(page);

    await option(page, 'Copy link to this page').click();

    await expect(palette(page)).toBeHidden();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toMatch(
      /\/sessions$/
    );
  });
});

test.describe('Command palette - finding a run', () => {
  test('three characters search the runs, fewer do not', async ({ page }) => {
    const mock = await mockPalette(page);
    await open(page);

    await search(page).fill('ab');
    await expect(
      palette(page).getByRole('group', { name: 'Runs' })
    ).toHaveCount(0);
    expect(mock.searches).toHaveLength(0);

    await search(page).fill('abc');

    const runs = palette(page).getByRole('group', { name: 'Runs' });
    await expect(runs.getByRole('option')).toHaveCount(1);
    await expect(runs).toContainText('abc12345');
    await expect(runs).toContainText('build-flow in build');
    await expect(runs.getByTestId('palette-status')).toBeVisible();
    expect(
      mock.searches[mock.searches.length - 1].searchParams.get('text')
    ).toBe('abc');
    expect(
      mock.searches[mock.searches.length - 1].searchParams.get('limit')
    ).toBe('5');
  });

  test('choosing a run opens it on its workflow', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await mockPalette(page);
    await open(page);
    await search(page).fill('abc');

    await option(page, 'abc12345').click();

    await expect(page).toHaveURL(
      /\/sessions\/build\/workflow\/build-flow\?exec=abc12345-0000-1111-2222-333344445555$/
    );
  });
});

test.describe('Command palette - on a run view', () => {
  const URL_RUN = '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11';

  test('offers this workflow: copy its CLI and MCP commands, who owns it, and stop the run in two presses', async ({
    page,
    mockWorkflowApi,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await mockWorkflowApi(page);
    await mockPalette(page);
    const stops: string[] = [];
    await page.route(/\/api\/executions\/[^/]+\/stop/, (r) => {
      stops.push(new URL(r.request().url()).pathname);
      return r.fulfill(envelope('/stop', {}));
    });
    await open(page, URL_RUN);
    const here = palette(page).getByRole('group', { name: 'This workflow' });
    await expect(here.getByRole('option')).toHaveText([
      /Copy CLI command to run it/,
      /Who owns this workflow\?/,
      /Copy MCP call to run it/,
      /Stop run exec-091/,
    ]);

    await option(page, 'Copy CLI command to run it').click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(
      'wf-982-xk-11'
    );

    await page.keyboard.press('Control+k');
    await option(page, 'Stop run exec-091').click();
    await expect(
      option(page, 'Press again to stop run exec-091')
    ).toBeVisible();
    expect(stops).toEqual([]);
    await option(page, 'Press again to stop run exec-091').click();
    await expect
      .poll(() => stops)
      .toEqual(['/api/executions/exec-091-qp-55/stop']);
  });

  test('Who owns this workflow? opens the supervision panel', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await mockPalette(page);
    await open(page, URL_RUN);

    await option(page, 'Who owns this workflow?').click();

    await expect(page).toHaveURL(/supervision=1/);
  });

  test('a finished run is not offered a stop', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page, {
      progress: { ...defaultProgress, status: 'SUCCESS' },
    });
    await mockPalette(page);
    await page.route('**/api/sessions/*/workflows/*/executions', (r) =>
      r.fulfill(
        envelope('/executions', [
          {
            executionId: 'exec-091-qp-55',
            workflowId: 'wf-982-xk-11',
            status: 'SUCCESS',
            startTime: '2026-07-26T14:22:01',
            endTime: '2026-07-26T14:23:01',
          },
        ])
      )
    );
    await open(page, URL_RUN);

    await expect(option(page, /Stop run/)).toHaveCount(0);
    await expect(option(page, 'Copy CLI command to run it')).toBeVisible();
  });

  test('is not shown off a run view', async ({ page }) => {
    await mockPalette(page);
    await open(page, '/plugins');

    await expect(
      palette(page).getByRole('group', { name: 'This workflow' })
    ).toHaveCount(0);
  });
});
