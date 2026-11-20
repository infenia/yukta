// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect, defaultProgress } from '../fixtures';
import { envelope } from '../api-envelope';
import { WorkflowDetailsPage } from '../pages/WorkflowDetailsPage';

const SESSION = 'SESSION_A92_XP_2024';
const WORKFLOW = 'wf-982-xk-11';
const EXEC = 'exec-091-qp-55';
const PAGE_URL = `/sessions/${SESSION}/workflow/${WORKFLOW}`;

const withStatus = (status: string, endTime: string | null = null) => ({
  ...defaultProgress,
  status,
  endTime,
});

/** Collects the POSTs of the run actions; each answers with a run id. */
async function recordActions(page: Page): Promise<string[]> {
  const calls: string[] = [];
  await page.route(
    /\/api\/executions\/[^/]+\/(pause|resume|stop|restart)(\?.*)?$/,
    (route) => {
      const url = new URL(route.request().url());
      calls.push(`${route.request().method()} ${url.pathname}${url.search}`);
      return route.fulfill(
        envelope('/action', { executionId: 'exec-restarted-1' })
      );
    }
  );
  await page.route(/\/api\/workflow\/[^/]+\/[^/]+\/stop(\?.*)?$/, (route) => {
    const url = new URL(route.request().url());
    calls.push(`${route.request().method()} ${url.pathname}${url.search}`);
    return route.fulfill(envelope('/stop', {}));
  });
  return calls;
}

test.describe('Run page - the run controls', () => {
  test.beforeEach(async ({ page }) => {
    await page.clock.setFixedTime(new Date('2026-07-26T14:23:23'));
  });

  test('a running run can be paused, and the call names the run', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    const calls = await recordActions(page);
    await page.goto(PAGE_URL);
    const run = new WorkflowDetailsPage(page);

    await expect(run.runControl('Pause')).toBeEnabled();
    await run.runControl('Pause').click();

    await expect
      .poll(() => calls)
      .toEqual([`POST /api/executions/${EXEC}/pause`]);
  });

  test('a paused run offers Resume instead of Pause', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page, { progress: withStatus('PAUSED') });
    const calls = await recordActions(page);
    await page.goto(PAGE_URL);
    const run = new WorkflowDetailsPage(page);

    await expect(run.runControl('Pause')).toHaveCount(0);
    await run.runControl('Resume').click();

    await expect
      .poll(() => calls)
      .toEqual([`POST /api/executions/${EXEC}/resume`]);
  });

  test('a finished run can be restarted, which opens the new run', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page, {
      progress: withStatus('SUCCESS', '2026-07-26T14:23:00'),
    });
    const calls = await recordActions(page);
    await page.goto(PAGE_URL);
    const run = new WorkflowDetailsPage(page);

    await expect(run.runControl('Pause')).toBeDisabled();
    await run.runControl('Restart').click();

    await expect(page).toHaveURL(/exec=exec-restarted-1/);
    expect(calls).toEqual([`POST /api/executions/${EXEC}/restart`]);
  });

  test('Restart is not offered while the run is live', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await page.goto(PAGE_URL);

    await expect(
      new WorkflowDetailsPage(page).runControl('Restart')
    ).toHaveCount(0);
  });

  test('Stop options offers stopping this run, cancelling and killing it', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await page.goto(PAGE_URL);

    await page.getByRole('button', { name: 'Stop options' }).click();

    for (const item of ['Stop this run', 'Cancel this run', 'Kill this run']) {
      await expect(page.getByRole('menuitem', { name: item })).toBeVisible();
    }
  });

  test('stopping a run asks first, and Cancel leaves it running', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    const calls = await recordActions(page);
    await page.goto(PAGE_URL);
    await page.getByRole('button', { name: 'Stop options' }).click();
    await page.getByRole('menuitem', { name: 'Stop this run' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();

    await expect(dialog).toBeHidden();
    expect(calls).toEqual([]);
  });

  test('confirming a stop sends it, and a kill sends its mode', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    const calls = await recordActions(page);
    await page.goto(PAGE_URL);

    await page.getByRole('button', { name: 'Stop options' }).click();
    await page.getByRole('menuitem', { name: 'Kill this run' }).click();
    await page
      .getByRole('dialog')
      .getByRole('button', { name: 'Confirm' })
      .click();

    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0]).toContain(`/api/executions/${EXEC}/stop`);
    expect(calls[0]).toContain('mode=KILL');
  });

  test('Copy as offers the run and every stop mode as commands', async ({
    page,
    mockWorkflowApi,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await mockWorkflowApi(page);
    await page.goto(PAGE_URL);

    await page.getByRole('button', { name: 'Copy as' }).click();
    await page.getByRole('menuitem', { name: 'Copy as CLI' }).click();
    for (const label of [
      'Run workflow',
      'Pause run',
      'Stop this run',
      'Stop all runs (DRAIN)',
      'Stop all runs (CANCEL)',
      'Stop all runs (KILL)',
    ]) {
      await expect(page.getByRole('menuitem', { name: label })).toBeVisible();
    }
    await page.getByRole('menuitem', { name: 'Pause run' }).click();

    expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(
      EXEC
    );
  });

  test('a VIEWER key cannot pause, stop or run', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await page.route('**/api/me', (route) =>
      route.fulfill(
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
    await page.goto(PAGE_URL);
    const run = new WorkflowDetailsPage(page);

    await expect(run.runControl('Pause')).toBeDisabled();
    await expect(
      page.getByRole('button', { name: 'Stop options' })
    ).toBeDisabled();
    await expect(run.runControl('Run workflow')).toBeDisabled();
  });
});

test.describe('Run page - the views', () => {
  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    await page.clock.setFixedTime(new Date('2026-07-26T14:23:23'));
    await mockWorkflowApi(page);
  });

  test('switches between the graph and the timeline of when each node ran', async ({
    page,
  }) => {
    await page.goto(PAGE_URL);
    const views = page.getByRole('radiogroup', { name: 'Run view' });
    await expect(views.getByRole('radio', { name: 'Graph' })).toBeChecked();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toBeVisible();

    await views.getByRole('radio', { name: 'Timeline' }).click();

    await expect(views.getByRole('radio', { name: 'Timeline' })).toBeChecked();
    await expect(page.getByText('When each node ran')).toBeVisible();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toHaveCount(0);

    await views.getByRole('radio', { name: 'Graph' }).click();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toBeVisible();
  });

  test('the canvas has zoom controls', async ({ page }) => {
    await page.goto(PAGE_URL);

    const zoom = page.getByRole('toolbar', { name: 'Zoom' });
    await expect(zoom.getByRole('button', { name: 'Zoom in' })).toBeVisible();
    await expect(zoom.getByRole('button', { name: 'Zoom out' })).toBeVisible();
    await expect(zoom.getByRole('button', { name: 'Fit view' })).toBeVisible();
    await zoom.getByRole('button', { name: 'Zoom in' }).click();
    await zoom.getByRole('button', { name: 'Fit view' }).click();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toBeVisible();
  });

  test('the canvas tools offer Find nodes and Follow running nodes', async ({
    page,
  }) => {
    await page.goto(PAGE_URL);

    const tools = page.getByRole('toolbar', { name: 'Canvas tools' });
    await expect(
      tools.getByRole('button', { name: 'Find nodes' })
    ).toBeVisible();
    const follow = tools.getByRole('button', { name: 'Follow running nodes' });
    await expect(follow).toHaveAttribute('aria-pressed', 'true');
    await follow.click();
    await expect(follow).toHaveAttribute('aria-pressed', 'false');
  });
});

test.describe('Run page - the node list and the logs', () => {
  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    await page.clock.setFixedTime(new Date('2026-07-26T14:23:23'));
    await mockWorkflowApi(page);
    await page.goto(PAGE_URL);
  });

  test('the task list filters nodes by name and says when none match', async ({
    page,
  }) => {
    const filter = page.getByLabel('Filter nodes');
    const list = page.getByTestId('task-sequence');
    await expect(list).toContainText('Data_Ingress');

    await filter.fill('vector');
    await expect(list).toContainText('Vectorize_Batch');
    await expect(list).not.toContainText('Data_Ingress');

    await filter.fill('zzz');
    await expect(list.getByText('No nodes match.')).toBeVisible();
  });

  test('the log level buttons keep only the lines of that level', async ({
    page,
  }) => {
    const logs = page.getByTestId('logs-body');
    const levels = page.getByRole('group', { name: 'Log level' });
    await expect(levels.getByRole('button', { name: 'all' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    await expect(logs).toContainText('Initializing workflow engine...');

    await levels.getByRole('button', { name: 'warn' }).click();

    await expect(levels.getByRole('button', { name: 'warn' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    await expect(logs).toContainText('High memory pressure');
    await expect(logs).not.toContainText('Initializing');

    await levels.getByRole('button', { name: 'all' }).click();
    await expect(logs).toContainText('Initializing workflow engine...');
  });

  test('the log tabs switch between logs, events and agents', async ({
    page,
  }) => {
    const tabs = page.getByRole('tab');
    await expect(page.getByRole('tab', { name: 'Logs' })).toHaveAttribute(
      'aria-selected',
      'true'
    );

    await page.getByRole('tab', { name: 'Events' }).click();
    await expect(page.getByRole('tab', { name: 'Events' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    await page.getByRole('tab', { name: 'Agents' }).click();
    await expect(page.getByRole('tab', { name: 'Agents' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(await tabs.count()).toBeGreaterThan(3);
  });

  test('the node status buttons filter the task list by status', async ({
    page,
  }) => {
    const list = page.getByTestId('task-sequence');
    const show = page.getByRole('group', { name: 'Show nodes' });
    await expect(show.getByRole('button', { name: 'All 4' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );

    await show.getByRole('button', { name: 'Waiting 2' }).click();

    await expect(list).toContainText('Anomaly_Detection');
    await expect(list).toContainText('Sink_Storage');
    await expect(list).not.toContainText('Data_Ingress');

    await show.getByRole('button', { name: 'Running 1' }).click();
    await expect(list).toContainText('Vectorize_Batch');
    await expect(list).not.toContainText('Anomaly_Detection');
  });

  test('the progress panel has Progress, Result and Details tabs', async ({
    page,
  }) => {
    await page.route('**/api/sessions/*/executions/*/results*', (route) =>
      route.fulfill(
        envelope('/results', {
          executionId: EXEC,
          complete: false,
          status: 'RUNNING',
          truncated: false,
          nextCursor: 0,
          events: [],
        })
      )
    );
    for (const name of ['Progress', 'Result', 'Details']) {
      await page.getByRole('tab', { name, exact: true }).click();
      await expect(
        page.getByRole('tab', { name, exact: true })
      ).toHaveAttribute('aria-selected', 'true');
    }
  });

  test('every node of the list has an actions menu', async ({ page }) => {
    await page
      .getByRole('button', { name: 'Actions for Vectorize_Batch' })
      .click();

    for (const item of ['Pause node', 'Stop node…', 'Set breakpoint…']) {
      await expect(page.getByRole('menuitem', { name: item })).toBeVisible();
    }
  });

  test('the logs can be maximised', async ({ page }) => {
    await page.getByRole('button', { name: 'Maximize the logs' }).click();

    await expect(
      page.getByRole('button', { name: /Restore the logs|Minimize the logs/ })
    ).toBeVisible();
  });
});

test.describe('Run page - node actions and the node sheet', () => {
  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    await page.clock.setFixedTime(new Date('2026-07-26T14:23:23'));
    await mockWorkflowApi(page);
  });

  /** Collects the POSTs a node action sends, answering each. */
  async function recordNodeCalls(page: Page): Promise<string[]> {
    const calls: string[] = [];
    await page.route(/\/api\/executions\/[^/]+\/nodes\//, (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      const url = new URL(route.request().url());
      calls.push(`${url.pathname}${url.search}`);
      return route.fulfill(envelope('/node', { executionId: EXEC }));
    });
    return calls;
  }

  test('Pause node from the actions menu sends pause for that node', async ({
    page,
  }) => {
    const calls = await recordNodeCalls(page);
    await page.goto(PAGE_URL);

    await page
      .getByRole('button', { name: 'Actions for Vectorize_Batch' })
      .click();
    await page.getByRole('menuitem', { name: 'Pause node' }).click();

    await expect
      .poll(() => calls.some((c) => c.includes('/nodes/Vectorize_Batch/pause')))
      .toBe(true);
  });

  test('a failed node offers Retry, and a waiting node offers Skip', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page, {
      progress: {
        ...defaultProgress,
        tasks: defaultProgress.tasks.map((t) =>
          t.nodeId === 'Vectorize_Batch'
            ? { ...t, status: 'FAILURE', endTime: '2026-07-26T14:23:00' }
            : t
        ),
      },
    });
    const calls = await recordNodeCalls(page);
    await page.goto(PAGE_URL);

    await page
      .getByRole('button', { name: 'Actions for Vectorize_Batch' })
      .click();
    await page.getByRole('menuitem', { name: 'Retry node' }).click();
    await expect
      .poll(() => calls.some((c) => c.includes('/nodes/Vectorize_Batch/retry')))
      .toBe(true);

    await page
      .getByRole('button', { name: 'Actions for Sink_Storage' })
      .click();
    await page.getByRole('menuitem', { name: 'Skip node' }).click();
    await expect
      .poll(() => calls.some((c) => c.includes('/nodes/Sink_Storage/skip')))
      .toBe(true);
  });

  test('a node opened with ?node= shows its details sheet, which Escape closes', async ({
    page,
  }) => {
    await page.route('**/api/executions/*/start-payload', (route) =>
      route.fulfill(envelope('/start-payload', { payload: {} }))
    );
    await page.route(/\/api\/sessions\/[^/]+\/workflows\/[^/]+$/, (route) =>
      route.fulfill(
        envelope('/workflow', {
          workflowId: WORKFLOW,
          description: 'Supply chain',
          nodes: [
            {
              nodeId: 'Vectorize_Batch',
              type: 'vector-engine-v2',
              config: { batch: 45 },
            },
          ],
          edges: [],
        })
      )
    );
    await page.goto(`${PAGE_URL}?node=Vectorize_Batch`);

    const sheet = page.getByTestId('node-sheet');
    await expect(sheet).toBeVisible();
    await expect(sheet).toContainText('Vectorize_Batch');

    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();
  });

  test('Find nodes lists the matching nodes and Escape closes it', async ({
    page,
  }) => {
    await page.goto(PAGE_URL);

    await page.getByRole('button', { name: 'Find nodes' }).click();
    await page.getByRole('searchbox', { name: 'Find nodes' }).fill('ingress');

    await expect(
      page.getByRole('list', { name: 'Matching nodes' })
    ).toContainText('Data_Ingress');
    await page.keyboard.press('Escape');
    await expect(
      page.getByRole('searchbox', { name: 'Find nodes' })
    ).toBeHidden();
  });
});
