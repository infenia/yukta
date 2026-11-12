// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page, TestInfo } from '@playwright/test';
import { test, expect } from '../fixtures';

// Scenarios of the view-first supervisors UI (design/plans/supervisor-framework.md, S8d). Each
// attaches screenshots to the report instead of comparing pixels: the committed snapshots are
// rendered in CI, and a local render differs.

const envelope = (path: string, data: unknown, status = 200) => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({
    timestamp: new Date().toISOString(),
    status,
    message: status < 400 ? 'OK' : 'connection refused',
    path,
    data,
  }),
});

const shot = async (page: Page, info: TestInfo, name: string) =>
  info.attach(name, {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });

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

const SUPERVISORS = [
  supervisor('release-lead', {
    type: 'http',
    description: 'The release agent.',
    settings: { url: 'https://agent.example.com/yukta', secretHeaders: '***' },
  }),
  supervisor('release-guard', {
    description: 'Pauses the release when deploys keep failing.',
    parents: ['release-lead'],
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

const assignment = (id: string, supervisorId: string, extra = {}) => ({
  id,
  supervisorId,
  scope: 'workflow:release/release-flow',
  enabled: true,
  source: 'MANUAL',
  originSessionId: null,
  originWorkflowId: null,
  ...extra,
});

const now = new Date().toISOString();
const DECISIONS = [
  {
    time: now,
    supervisorId: 'release-guard',
    outcome: 'applied',
    command: 'PauseWorkflowCommand',
    executionId: '8f2c41aa-run',
    reason: null,
    cause: 'initiative',
    key: 'pause',
    because: '3 failures in 60 s',
    workflowId: 'release-flow',
  },
  ...['build.compile', 'build.test'].map((nodeId, i) => ({
    time: now,
    supervisorId: 'release-lead',
    outcome: i === 0 ? 'applied' : 'not-owner',
    command: 'RetryNodeCommand',
    executionId: `run-${i}`,
    reason: i === 0 ? null : 'not owned',
    nodeId,
    cause: 'event',
    key: `retry#run-${i}`,
    because: 'flaky test',
    workflowId: 'build',
  })),
];

const SECRET = 'yag_e2e-secret-shown-once';

/** The supervisors page's endpoints, with one conflict and a credential not issued yet. */
async function mockSupervisors(page: Page) {
  let issued = false;
  await page.route(/\/api\/supervisors$/, (r) =>
    r.fulfill(envelope('/supervisors', SUPERVISORS))
  );
  await page.route(/\/api\/supervisors\/assignments(\?|$)/, (r) =>
    r.fulfill(
      envelope('/assignments', [
        assignment('a1', 'release-guard'),
        assignment('a2', 'release-lead', { enabled: false, conflict: true }),
      ])
    )
  );
  await page.route(/\/api\/supervisors\/[^/]+\/assignments$/, (r) =>
    r.fulfill(envelope('/assignments', [assignment('a1', 'release-guard')]))
  );
  await page.route(/\/api\/supervisor-engines$/, (r) =>
    r.fulfill(envelope('/engines', ENGINES))
  );
  await page.route(/\/api\/supervisor-engines\/[^/?]+$/, (r) =>
    r.fulfill(
      envelope('/engine', {
        ...ENGINES[1],
        defaultAllowedCommands: null,
        configSchema: [
          {
            name: 'url',
            type: 'STRING',
            required: false,
            defaultValue: null,
            options: [],
            description: 'Where each signal is posted',
          },
        ],
        commonSchema: [],
      })
    )
  );
  await page.route(/\/api\/supervisors\/release-guard\/engine\/rules$/, (r) =>
    r.fulfill(
      envelope('/rules', {
        version: 7,
        rules: {
          schemaVersion: 1,
          rules: [
            {
              id: 'pause-on-failures',
              when: {
                event: 'NodeStatusChanged',
                status: 'FAILURE',
                count: 3,
                withinSeconds: 60,
              },
              then: { command: 'PauseWorkflow', reason: 'too many failures' },
            },
          ],
        },
      })
    )
  );
  await page.route(
    /\/api\/supervisors\/release-lead\/credential(\?|$)/,
    (r) => {
      if (r.request().method() === 'POST') {
        issued = true;
        return r.fulfill(
          envelope('/credential', { token: SECRET, expiresAt: null }, 201)
        );
      }
      return issued
        ? r.fulfill(
            envelope('/credential', {
              issuedAt: now,
              expiresAt: null,
              addresses: [],
              lastUsedAt: null,
              lastUsedFrom: null,
            })
          )
        : r.fulfill(envelope('/credential', null, 404));
    }
  );
  await page.route(/\/api\/supervisors\/[^/]+\/versions$/, (r) =>
    r.fulfill(envelope('/versions', []))
  );
  await page.route(/\/api\/supervisors\/decisions(\?|$)/, (r) =>
    r.fulfill(envelope('/decisions', DECISIONS))
  );
}

test.describe('Supervisors', () => {
  test('shows who supervises what, a conflict, rules as sentences and what they did', async ({
    page,
  }, info) => {
    await mockSupervisors(page);
    await page.goto('/supervisors');

    await expect(page.getByText(/1 assignment overlaps/)).toBeVisible();
    const nav = page.getByRole('navigation', { name: 'Supervisors' });
    await nav
      .getByRole('button', { name: /release-guard/ })
      .first()
      .click();
    await expect(
      page.getByRole('heading', { name: 'release-guard' })
    ).toBeVisible();
    await shot(page, info, 'supervisor');

    await page
      .getByRole('tablist', { name: 'release-guard details' })
      .getByRole('tab', { name: 'Settings' })
      .click();
    await expect(
      page.getByText('a node turns FAILURE at least 3 times within 60 s', {
        exact: false,
      })
    ).toBeVisible();
    await expect(
      page.getByText('pause the run · reason “too many failures”')
    ).toBeVisible();
    await shot(page, info, 'rules-settings');

    await page
      .getByRole('tablist', { name: 'release-guard details' })
      .getByRole('tab', { name: 'Decisions' })
      .click();
    const activity = page.getByRole('list', { name: 'Activity' });
    await expect(activity).toContainText('on its own');
    await expect(activity).toContainText('Reason: 3 failures in 60 s');
    await expect(activity).toContainText('1 of 2 applied');
    await expect(
      activity.getByRole('list', { name: 'Per target' })
    ).toContainText('not-owner: not owned');
    await shot(page, info, 'activity');

    await page.getByRole('button', { name: 'Decision maker types' }).click();
    await expect(page.getByText('External system').first()).toBeVisible();
    await shot(page, info, 'decision-makers');
  });

  test('shows a new credential once and never again', async ({
    page,
  }, info) => {
    await mockSupervisors(page);
    await page.goto('/supervisors?supervisor=release-lead');
    await page
      .getByRole('tablist', { name: 'release-lead details' })
      .getByRole('tab', { name: 'Settings' })
      .click();

    await expect(page.getByText(/Out:/)).toBeVisible();
    const panel = page.getByRole('region', { name: 'Credential' });
    await expect(panel).toContainText('None');
    await panel.getByRole('button', { name: /Create/ }).click();
    await expect(page.getByText(SECRET)).toBeVisible();
    await expect(panel).toContainText('Active');
    await shot(page, info, 'credential-once');

    await page.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByText(SECRET)).toHaveCount(0);
    expect(await page.content()).not.toContain(SECRET);
    const stored = await page.evaluate(() =>
      JSON.stringify({ ...localStorage, ...sessionStorage })
    );
    expect(stored).not.toContain(SECRET);
  });

  test('says when there are no supervisors, and when they cannot be loaded', async ({
    page,
  }, info) => {
    await page.goto('/supervisors');
    await expect(page.getByText(/No supervisors yet/)).toBeVisible();
    await shot(page, info, 'empty');

    let fail = true;
    await page.route(/\/api\/supervisors$/, (r) =>
      fail
        ? r.fulfill(envelope('/supervisors', null, 500))
        : r.fulfill(envelope('/supervisors', SUPERVISORS))
    );
    await page.goto('/supervisors');
    await expect(
      page.getByText('Failed to load the supervisors.')
    ).toBeVisible();
    await shot(page, info, 'error');
    fail = false;
    await page.getByRole('button', { name: /Retry/ }).click();
    await expect(
      page.getByRole('navigation', { name: 'Supervisors' })
    ).toContainText('release-lead');
  });
});

test.describe('Supervision on a workflow', () => {
  const WORKFLOW_URL = '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11';

  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page);
    await page.route(/\/api\/supervision\/ownership(\?|$)/, (r) =>
      r.fulfill(
        envelope('/ownership', {
          sessionId: 'SESSION_A92_XP_2024',
          workflowId: 'wf-982-xk-11',
          defaultSupervisor: true,
          run: 'release-lead',
          nodes: {},
        })
      )
    );
  });

  test('the palette answers who owns this workflow', async ({ page }, info) => {
    await page.goto(WORKFLOW_URL);
    await expect(
      page.getByRole('button', { name: /Search and jump to/ })
    ).toBeVisible();
    await page.keyboard.press('Control+k');
    await page.getByRole('combobox').fill('who owns');
    // The palette filters a deferred query, so Enter must wait for the filtered list
    await expect(page.getByRole('option').first()).toContainText(
      'Who owns this workflow'
    );
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/supervision=1/);
    await expect(
      page.getByRole('dialog', { name: 'Who controls this workflow' })
    ).toBeVisible();
    await shot(page, info, 'supervision-dialog');
  });

  test('an input request says how it got to you', async ({ page }, info) => {
    await page.route('**/api/sessions/*/inputs*', (r) =>
      r.fulfill(
        envelope('/inputs', [
          {
            id: 'req-1',
            sessionId: 'SESSION_A92_XP_2024',
            workflowId: 'wf-982-xk-11',
            executionId: 'exec-091-qp-55',
            nodeId: 'Sink_Storage',
            state: 'ESCALATED',
            version: 3,
            kind: 'APPROVAL',
            prompt: 'Stop the release?',
            context: {},
            options: [],
            responseSchema: null,
            risk: 'MEDIUM',
            tier: 'people',
            advice: null,
            deadline: null,
            createdAt: now,
            updatedAt: now,
            answer: null,
            asked: [
              {
                who: 'build-agent',
                said: 'asked for approval',
                reason: null,
                at: now,
              },
              { who: 'release-lead', said: 'silent', reason: null, at: now },
            ],
          },
        ])
      )
    );
    await page.goto(WORKFLOW_URL);
    const panel = page.getByRole('region', { name: 'Inputs' });
    await expect(panel.getByLabel('Approval chain')).toHaveText(
      'Asked build-agent (asked for approval), then release-lead (silent), now you.'
    );
    await shot(page, info, 'approval-chain');
  });
});
