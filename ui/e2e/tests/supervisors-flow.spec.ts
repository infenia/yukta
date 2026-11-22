// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const supervisor = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  type: 'rules',
  description: null,
  settings: {},
  dryRun: false,
  system: false,
  attachments: 1,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
  parents: [],
  delegation: {},
  ...extra,
});

const LIST = [
  supervisor('release-lead', {
    type: 'http',
    description: 'The release agent.',
    attachments: 4,
    applied: 12,
    rejected: 3,
    rateLimited: 2,
  }),
  supervisor('audit-bot', {
    description: 'Only records what it would do.',
    dryRun: true,
    attachments: 0,
  }),
  supervisor('built-in', {
    system: true,
    description: 'Configured by the server.',
  }),
];

const ENGINES = [
  { type: 'rules', displayName: 'Rules', category: 'RULES', icon: 'rule' },
  {
    type: 'http',
    displayName: 'External system',
    category: 'EXTERNAL_SYSTEM',
    icon: 'api',
  },
];

const now = new Date().toISOString();
const DECISIONS = [
  {
    time: now,
    supervisorId: 'release-lead',
    outcome: 'applied',
    command: 'PauseWorkflowCommand',
    executionId: '8f2c41aa-run',
    reason: null,
    cause: 'initiative',
    key: 'pause',
    because: '3 failures in 60 s',
    workflowId: 'release-flow',
  },
];

interface Options {
  list?: unknown[] | null;
  listStatus?: number;
  unowned?: { sessionId: string; workflowId: string }[];
  summaryStatus?: number;
  defaultOff?: boolean;
}

async function mockSupervisors(
  page: Page,
  {
    list = LIST,
    listStatus = 200,
    unowned = [],
    summaryStatus = 200,
    defaultOff = false,
  }: Options = {}
) {
  await page.route(/\/api\/supervisors$/, (r) =>
    listStatus === 200
      ? r.fulfill(envelope('/supervisors', list))
      : r.fulfill({
          status: listStatus,
          contentType: 'application/json',
          body: '{"message":"supervisor store is down"}',
        })
  );
  await page.route(/\/api\/supervisors\/assignments(\?|$)/, (r) =>
    r.fulfill(envelope('/assignments', []))
  );
  await page.route(/\/api\/supervisors\/[^/]+\/assignments$/, (r) =>
    r.fulfill(
      envelope('/assignments', [
        {
          id: 'a1',
          supervisorId: 'release-lead',
          scope: 'workflow:release/release-flow',
          enabled: true,
          source: 'MANUAL',
          originSessionId: null,
          originWorkflowId: null,
        },
      ])
    )
  );
  await page.route(/\/api\/supervisor-engines$/, (r) =>
    r.fulfill(envelope('/engines', ENGINES))
  );
  await page.route(/\/api\/supervisor-engines\/[^/?]+$/, (r) =>
    r.fulfill(
      envelope('/engine', {
        ...ENGINES[1],
        defaultAllowedCommands: null,
        configSchema: [],
        commonSchema: [],
      })
    )
  );
  await page.route(/\/api\/supervisors\/[^/]+\/versions$/, (r) =>
    r.fulfill(envelope('/versions', []))
  );
  await page.route(/\/api\/supervisors\/decisions(\?|$)/, (r) =>
    r.fulfill(envelope('/decisions', DECISIONS))
  );
  await page.route(/\/api\/supervisors\/[^/]+\/credential(\?|$)/, (r) =>
    r.fulfill(envelope('/credential', null, 404))
  );
  await page.route(/\/api\/supervision\/summary$/, (r) =>
    summaryStatus === 200
      ? r.fulfill(
          envelope('/summary', {
            unowned: unowned.length,
            unownedWorkflows: unowned,
          })
        )
      : r.fulfill({
          status: summaryStatus,
          contentType: 'application/json',
          body: '{"message":"no summary"}',
        })
  );
  await page.route(/\/api\/supervision\/ownership(\?|$)/, (r) =>
    r.fulfill(
      envelope('/ownership', {
        sessionId: 's',
        workflowId: 'w',
        defaultSupervisor: !defaultOff,
        run: 'default',
        nodes: {},
      })
    )
  );
}

const nav = (page: Page) =>
  page.getByRole('navigation', { name: 'Supervisors' });
const item = (page: Page, name: string | RegExp) =>
  nav(page).getByRole('button', { name });
const detail = (page: Page) => page.getByRole('article');

test.describe('Supervisors - the page', () => {
  test.beforeEach(async ({ page }) => {
    await mockSupervisors(page);
  });

  test('has a title, an explanation and links to the activity and to a new supervisor', async ({
    page,
  }) => {
    await page.goto('/supervisors');

    await expect(
      page.getByRole('heading', { level: 1, name: 'Supervisors' })
    ).toBeVisible();
    await expect(page.getByText('Who watches your workflows')).toBeVisible();
    await expect(
      page.getByRole('link', { name: 'Control activity' })
    ).toHaveAttribute('href', '/control?tab=activity');
    await expect(
      page.getByRole('link', { name: 'New supervisor' })
    ).toHaveAttribute('href', '/supervisors/new');
  });

  test('the old activity tab link goes to the Control activity', async ({
    page,
  }) => {
    await page.goto('/supervisors?tab=activity');

    await expect(page).toHaveURL(/\/control\?tab=activity$/);
  });

  test('Decision maker types opens a panel and closing it clears the URL', async ({
    page,
  }) => {
    await page.goto('/supervisors');

    await page.getByRole('button', { name: 'Decision maker types' }).click();
    const sheet = page.getByRole('dialog', { name: 'Decision maker types' });
    await expect(sheet).toBeVisible();
    await expect(page).toHaveURL(/tab=decision-makers/);

    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();
    await expect(page).not.toHaveURL(/tab=decision-makers/);
  });

  test('a link to the panel opens it', async ({ page }) => {
    await page.goto('/supervisors?tab=decision-makers');

    await expect(
      page.getByRole('dialog', { name: 'Decision maker types' })
    ).toBeVisible();
  });
});

test.describe('Supervisors - the list', () => {
  test('lists the supervisors by ID with their type, state and what they own, and Direct control last', async ({
    page,
  }) => {
    await mockSupervisors(page);
    await page.goto('/supervisors');

    const buttons = nav(page).getByRole('button');
    await expect(buttons).toHaveCount(4);
    await expect(buttons.nth(0)).toContainText('audit-bot');
    await expect(buttons.nth(1)).toContainText('built-in');
    await expect(buttons.nth(2)).toContainText('release-lead');
    await expect(buttons.nth(3)).toContainText('Direct control');
    await expect(buttons.nth(3)).toContainText('For workflows nobody owns');
    await expect(item(page, /release-lead/)).toContainText('http');
    await item(page, /release-lead/)
      .getByText('4', { exact: true })
      .hover();
    await expect(page.getByText('Owns 4 targets')).toBeVisible();
    await expect(
      item(page, /release-lead/).getByRole('img', { name: 'Live' })
    ).toBeVisible();
    await expect(
      item(page, /audit-bot/).getByRole('img', { name: 'Dry run' })
    ).toBeVisible();
    await expect(item(page, /audit-bot/)).not.toContainText(/\d/);
  });

  test('the first supervisor is selected until another is chosen, and the choice is in the URL', async ({
    page,
  }) => {
    await mockSupervisors(page);
    await page.goto('/supervisors');
    await expect(item(page, /audit-bot/)).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(
      detail(page).getByRole('heading', { name: 'audit-bot' })
    ).toBeVisible();

    await item(page, /release-lead/).click();

    await expect(page).toHaveURL(/supervisor=release-lead/);
    await expect(item(page, /release-lead/)).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(
      detail(page).getByRole('heading', { name: 'release-lead' })
    ).toBeVisible();
  });

  test('a link to a supervisor selects it', async ({ page }) => {
    await mockSupervisors(page);
    await page.goto('/supervisors?supervisor=release-lead');

    await expect(item(page, /release-lead/)).toHaveAttribute(
      'aria-current',
      'true'
    );
  });

  test('the filter narrows the list by name and keeps Direct control', async ({
    page,
  }) => {
    await mockSupervisors(page);
    await page.goto('/supervisors');
    const filter = page.getByLabel('Filter supervisors');

    await filter.fill('RELEASE');
    await expect(nav(page).getByRole('button')).toHaveCount(2);
    await expect(item(page, /release-lead/)).toBeVisible();

    await filter.fill('zzz');
    await expect(nav(page).getByRole('button')).toHaveCount(1);
    await expect(item(page, /Direct control/)).toBeVisible();
  });

  test('with no supervisors it explains how they are added and shows Direct control', async ({
    page,
  }) => {
    await mockSupervisors(page, { list: [] });
    await page.goto('/supervisors');

    await expect(page.getByText(/No supervisors yet\./)).toBeVisible();
    await expect(page.getByText('save_supervisor')).toBeVisible();
    await expect(
      detail(page).getByRole('heading', { name: 'Direct control' })
    ).toBeVisible();
  });

  test('shows a loading state while the supervisors load', async ({ page }) => {
    await mockSupervisors(page);
    await page.route(/\/api\/supervisors$/, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      return route.fulfill(envelope('/supervisors', LIST));
    });
    await page.goto('/supervisors');

    await expect(page.getByText('Loading supervisors…')).toBeVisible();
    await expect(item(page, /release-lead/)).toBeVisible();
  });

  test('a failure shows why and Retry loads them again', async ({ page }) => {
    await mockSupervisors(page, { listStatus: 500 });
    await page.goto('/supervisors');
    await expect(
      page.getByText('Failed to load the supervisors.')
    ).toBeVisible();
    await expect(page.getByText('supervisor store is down')).toBeVisible();

    await mockSupervisors(page);
    await page.getByRole('button', { name: 'Retry' }).click();

    await expect(item(page, /release-lead/)).toBeVisible();
  });
});

test.describe('Supervisors - one supervisor', () => {
  test.beforeEach(async ({ page }) => {
    await mockSupervisors(page);
  });

  test('shows its ID, engine, state and description', async ({ page }) => {
    await page.goto('/supervisors?supervisor=release-lead');

    const article = detail(page);
    await expect(
      article.getByRole('heading', { name: 'release-lead' })
    ).toBeVisible();
    await expect(article.locator('code', { hasText: 'http' })).toBeVisible();
    await expect(article).toContainText('Live');
    await expect(article.getByText('The release agent.')).toBeVisible();
  });

  test('a dry-run supervisor says it only records', async ({ page }) => {
    await page.goto('/supervisors?supervisor=audit-bot');

    await expect(
      detail(page).getByText('Dry run: recorded, not applied')
    ).toBeVisible();
  });

  test('a built-in supervisor is read only and has no Edit link', async ({
    page,
  }) => {
    await page.goto('/supervisors?supervisor=built-in');

    await expect(detail(page).getByText('Built in (read only)')).toBeVisible();
    await expect(
      page.getByRole('link', { name: 'Edit supervisor' })
    ).toHaveCount(0);
  });

  test('a normal supervisor links to its editor', async ({ page }) => {
    await page.goto('/supervisors?supervisor=release-lead');

    await expect(
      page.getByRole('link', { name: 'Edit supervisor' })
    ).toHaveAttribute('href', '/supervisors/release-lead/edit');
  });

  test('the key numbers show what it applied, refused and owns', async ({
    page,
  }) => {
    await page.goto('/supervisors?supervisor=release-lead');

    const glance = page.getByRole('region', { name: 'At a glance' });
    await expect(glance).toContainText('Applied');
    await expect(glance).toContainText('12');
    await expect(glance).toContainText('Refused');
    await expect(glance).toContainText('3');
    await expect(glance).toContainText('Rate limited');
    await expect(glance).toContainText('2');
    await expect(glance).toContainText('Last decision');
    await expect(glance).toContainText(/\d+ workflow/);
  });

  test('shows every part of the supervisor on one page, with the number it owns and its decisions', async ({
    page,
  }) => {
    await page.goto('/supervisors?supervisor=release-lead');

    await expect(page.getByRole('tablist')).toHaveCount(0);
    for (const name of [
      'Owns',
      'Allowed operations',
      'Hierarchy',
      'Decision maker',
      'Decisions',
    ]) {
      await expect(page.getByRole('region', { name })).toBeVisible();
    }
    await expect(page.getByRole('group', { name: 'Changes' })).toBeVisible();
    await expect(
      page.getByRole('heading', { name: /^Owns\s*4/ })
    ).toBeVisible();
    await expect(
      page.getByRole('heading', { name: /^Decisions\s*1/ })
    ).toBeVisible();
  });

  for (const [section, label] of [
    ['operations', 'Allowed operations'],
    ['hierarchy', 'Hierarchy'],
    ['decision-maker', 'Decision maker'],
    ['decisions', 'Decisions'],
  ]) {
    test(`a link to the ${label} section is kept in the URL`, async ({
      page,
    }) => {
      await page.goto(
        `/supervisors?supervisor=release-lead&section=${section}`
      );

      await expect(page.getByRole('region', { name: label })).toBeVisible();
    });
  }

  test('the Changes section opens from a link, and the old Settings link goes to Decision maker', async ({
    page,
  }) => {
    await page.goto('/supervisors?supervisor=release-lead&section=changes');
    await expect(page.getByRole('group', { name: 'Changes' })).toHaveAttribute(
      'open',
      ''
    );

    await page.goto('/supervisors?supervisor=release-lead&section=settings');
    await expect(
      page.getByRole('region', { name: 'Decision maker' })
    ).toBeVisible();
  });

  test('choosing another supervisor clears the section from the URL', async ({
    page,
  }) => {
    await page.goto(
      '/supervisors?supervisor=release-lead&section=decision-maker'
    );

    await item(page, /audit-bot/).click();

    await expect(page).not.toHaveURL(/section=/);
    await expect(page).toHaveURL(/supervisor=audit-bot/);
  });

  test('the Owns section names what it is assigned to', async ({ page }) => {
    await page.goto('/supervisors?supervisor=release-lead');

    await expect(page.getByRole('region', { name: 'Owns' })).toContainText(
      'release-flow'
    );
  });

  test('the Decisions section lists what it decided', async ({ page }) => {
    await page.goto('/supervisors?supervisor=release-lead&section=decisions');

    const panel = page.getByRole('region', { name: 'Decisions' });
    await expect(panel).toContainText('release-flow');
    await expect(panel).toContainText('3 failures in 60 s');
  });
});

test.describe('Supervisors - Direct control', () => {
  test('lists the workflows nobody owns, each linking to its run view', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await mockSupervisors(page, {
      unowned: [
        { sessionId: 'build', workflowId: 'check' },
        { sessionId: 'deploy', workflowId: 'ship' },
      ],
    });
    await page.goto('/supervisors?supervisor=default');

    await expect(item(page, /Direct control/)).toHaveAttribute(
      'aria-current',
      'true'
    );
    const article = detail(page);
    await expect(
      article.getByRole('heading', { name: 'Direct control' })
    ).toBeVisible();
    await expect(
      article.getByText('Controls the workflows that no supervisor owns.')
    ).toBeVisible();
    await expect(
      article.getByRole('heading', { name: /^Owns\s*2/ })
    ).toBeVisible();
    for (const name of ['Allowed operations', 'Decision maker']) {
      await expect(article.getByRole('region', { name })).toBeVisible();
    }
    await article.getByRole('link', { name: 'build / check' }).click();

    await expect(page).toHaveURL(/\/sessions\/build\/workflow\/check$/);
  });

  test('says when every workflow has a supervisor', async ({ page }) => {
    await mockSupervisors(page);
    await page.goto('/supervisors?supervisor=default');

    await expect(page.getByText('Nothing is unowned')).toBeVisible();
    await expect(
      page.getByText('Every workflow has a supervisor.')
    ).toBeVisible();
  });

  test('Allowed operations says what may be sent, or that direct control is off', async ({
    page,
  }) => {
    await mockSupervisors(page, {
      unowned: [{ sessionId: 'build', workflowId: 'check' }],
    });
    await page.goto('/supervisors?supervisor=default&section=operations');
    await expect(
      page.getByText(/people and agents may send any command/)
    ).toBeVisible();

    await mockSupervisors(page, {
      unowned: [{ sessionId: 'build', workflowId: 'check' }],
      defaultOff: true,
    });
    await page.reload();
    await expect(
      page.getByText(/Direct control is switched off on this server/)
    ).toBeVisible();
  });

  test('Decision maker says people and agents decide', async ({ page }) => {
    await mockSupervisors(page);
    await page.goto('/supervisors?supervisor=default&section=decision-maker');

    await expect(
      page.getByRole('region', { name: 'Decision maker' })
    ).toContainText('the inbox, the control bar and the API');
  });

  test('reads the old Settings section as Decision maker', async ({ page }) => {
    await mockSupervisors(page);
    await page.goto('/supervisors?supervisor=default&section=settings');

    await expect(
      page.getByRole('region', { name: 'Decision maker' })
    ).toBeVisible();
    await expect(page.getByRole('region', { name: 'Owns' })).toBeVisible();
  });

  test('says when what nobody owns cannot be loaded', async ({ page }) => {
    await mockSupervisors(page, { summaryStatus: 500 });
    await page.goto('/supervisors?supervisor=default');

    await expect(
      page.getByText('Could not load what nobody owns.')
    ).toBeVisible();
  });
});

test.describe('Supervisors - on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('shows the chosen supervisor first, with the list behind a button', async ({
    page,
  }) => {
    await mockSupervisors(page);
    await page.goto('/supervisors?supervisor=release-lead');

    await expect(
      detail(page).getByRole('heading', { name: 'release-lead' })
    ).toBeInViewport();
    await expect(nav(page)).toBeHidden();

    const toggle = page.getByRole('button', { name: /^All supervisors/ });
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await item(page, /audit-bot/).click();

    await expect(page).toHaveURL(/supervisor=audit-bot/);
    await expect(nav(page)).toBeHidden();
    await expect(
      detail(page).getByRole('heading', { name: 'audit-bot' })
    ).toBeFocused();
  });
});
