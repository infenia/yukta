// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect, defaultProgress, sseBody } from '../fixtures';

const WORKFLOW_URL = '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11';

const screenshotOptions = {
  full: { maxDiffPixels: 2000, threshold: 0.2 },
  partial: { maxDiffPixels: 1000, threshold: 0.2 },
};

test.describe('Workflow Details Page', () => {
  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    // Freeze the clock just after the mocked execution start so live durations
    // ("Dur: 1m 12s") render deterministically in assertions and screenshots.
    await page.clock.setFixedTime(new Date('2026-07-26T14:23:23'));
    await mockWorkflowApi(page);
  });

  test('renders the running workflow with canvas, panel and logs', async ({
    page,
  }) => {
    await page.goto(WORKFLOW_URL);

    await expect(page.getByTestId('workflow-details-page')).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'wf-982-xk-11' })
    ).toBeVisible();
    await expect(page.getByTestId('status-chip')).toHaveText('Running');

    await expect(page.getByTestId('workflow-node-Data_Ingress')).toBeVisible();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toContainText(
      'Succeeded'
    );
    await expect(
      page.getByTestId('workflow-node-Vectorize_Batch')
    ).toContainText('Running');
    await expect(page.getByTestId('workflow-node-Sink_Storage')).toContainText(
      'Waiting'
    );

    await expect(page.getByTestId('task-sequence')).toContainText('Succeeded');
    await expect(page.getByTestId('task-sequence')).toContainText(
      'module: vector-engine-v2'
    );
    await expect(page.getByTestId('task-sequence')).toContainText(
      'Dur: 1m 12s'
    );

    // the run's IDs and times sit on the Details tab
    await page.getByRole('tab', { name: 'Details' }).click();
    await expect(page.getByTestId('execution-summary')).toContainText(
      'exec-091-qp-55'
    );
    await expect(page.getByTestId('execution-summary')).toContainText(
      'SESSION_A92_XP_2024'
    );
    await expect(page.getByTestId('execution-summary')).toContainText(
      '--:--:--'
    );

    await expect(page.getByTestId('logs-body')).toContainText(
      'Initializing workflow engine...'
    );
    await expect(page.getByTestId('logs-body')).toContainText(
      'High memory pressure detected on worker-node-04'
    );
  });

  test('shows the breadcrumb trail, and the run history in the title row', async ({
    page,
  }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('workflow-details-page')).toBeVisible();

    const runHistory = page.getByRole('button', { name: 'Run history' });
    // the first 8 characters of the run ID, as in the Runs list
    await expect(runHistory).toContainText('exec-091');
    // the trail sits above the title, in the page: Sessions › session › workflow
    const trail = page.getByRole('navigation', { name: 'breadcrumb' });
    await expect(
      trail.getByRole('link', { name: 'SESSION_A92_XP_2024' })
    ).toBeVisible();
    await expect(trail.getByText('wf-982-xk-11')).toHaveAttribute(
      'aria-current',
      'page'
    );
    await expect(
      page.getByRole('heading', { name: 'wf-982-xk-11' })
    ).toBeVisible();
    await expect(page.getByText('Supply_Chain_Optimizer_V2')).toBeVisible();
  });

  test('closes and reopens the workflow progress panel from the header', async ({
    page,
  }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('progress-panel')).toBeVisible();

    await page
      .getByRole('button', { name: 'Close workflow progress panel' })
      .click();
    await expect(page.getByTestId('progress-panel')).toBeHidden();

    await page.getByRole('button', { name: 'Workflow progress' }).click();
    await expect(page.getByTestId('progress-panel')).toBeVisible();
  });

  test('updates node status live from the status stream', async ({ page }) => {
    let release: () => void = () => undefined;
    const released = new Promise<void>((resolve) => {
      release = resolve;
    });
    const advanced = {
      ...defaultProgress,
      tasks: defaultProgress.tasks.map((task) =>
        task.nodeId === 'Vectorize_Batch'
          ? { ...task, status: 'SUCCESS', endTime: '2026-07-26T14:23:20' }
          : task
      ),
    };
    // Hold the stream open until the snapshot has rendered, then push the frame.
    await page.route('**/api/executions/*/status/stream*', async (route) => {
      await released;
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody([advanced]),
      });
    });

    await page.goto(WORKFLOW_URL);
    const node = page.getByTestId('workflow-node-Vectorize_Batch');
    await expect(node).toContainText('Running');
    await page.evaluate(() => {
      (window as unknown as { notReloaded: boolean }).notReloaded = true;
    });

    release();

    await expect(node).toContainText('Succeeded');
    expect(
      await page.evaluate(
        () => (window as unknown as { notReloaded?: boolean }).notReloaded
      )
    ).toBe(true);
  });

  test('collapses the execution logs panel', async ({ page }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('logs-body')).toBeVisible();

    await page.getByRole('button', { name: 'Collapse execution logs' }).click();
    await expect(page.getByTestId('logs-body')).toBeHidden();

    await page.getByRole('button', { name: 'Expand execution logs' }).click();
    await expect(page.getByTestId('logs-body')).toBeVisible();
  });

  test('filters execution logs', async ({ page }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('logs-body')).toContainText(
      'Initializing workflow engine...'
    );

    await page.getByLabel('Filter logs').fill('memory');
    await expect(page.getByTestId('logs-body')).toContainText(
      'High memory pressure'
    );
    await expect(page.getByTestId('logs-body')).not.toContainText(
      'Initializing'
    );
  });

  test('switches runs through the run history dropdown', async ({ page }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('workflow-details-page')).toBeVisible();

    await page.getByRole('button', { name: 'Run history' }).click();
    await page.getByText('exec-090-aa-12').click();

    await expect(page).toHaveURL(/exec=exec-090-aa-12/);
    await expect(
      page.getByRole('button', { name: 'Run history' })
    ).toContainText('exec-090');
  });

  test('stops a node immediately with a reason', async ({ page }) => {
    const stops: string[] = [];
    await page.route('**/api/executions/*/nodes/*/stop*', (route) => {
      stops.push(new URL(route.request().url()).search);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: '2026-07-26T14:23:23',
          status: 200,
          message: 'OK',
          path: '/stop',
          data: { executionId: 'exec-091-qp-55' },
        }),
      });
    });
    await page.goto(WORKFLOW_URL);

    await page
      .getByRole('button', { name: 'Actions for Vectorize_Batch' })
      .click();
    await page.getByRole('menuitem', { name: 'Stop node…' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('recorded in the control audit');
    await dialog.getByLabel('Stop immediately (do not drain)').check();
    await dialog.getByLabel('Reason (optional)').fill('too slow');
    await dialog.getByRole('button', { name: 'Stop', exact: true }).click();

    await expect(dialog).toBeHidden();
    expect(stops).toEqual(['?immediate=true&reason=too+slow']);
  });

  test('sets and clears a breakpoint on a node', async ({ page }) => {
    const calls: string[] = [];
    // The marker comes from the status snapshot the server sends, so the mock serves the breakpoint it stored.
    let breakpoint: { field: string; equals: string } | null = null;
    await page.route('**/api/executions/*/status/stream*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody([
          {
            ...defaultProgress,
            tasks: defaultProgress.tasks.map((task) =>
              task.nodeId === 'Vectorize_Batch' ? { ...task, breakpoint } : task
            ),
          },
        ]),
      })
    );
    await page.route('**/api/executions/*/nodes/*/breakpoint*', (route) => {
      const request = route.request();
      const url = new URL(request.url());
      calls.push(`${request.method()} ${url.search}`);
      breakpoint =
        request.method() === 'POST'
          ? {
              field: url.searchParams.get('field') ?? '',
              equals: url.searchParams.get('equals') ?? '',
            }
          : null;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: '2026-07-26T14:23:23',
          status: 200,
          message: 'OK',
          path: '/breakpoint',
          data: { executionId: 'exec-091-qp-55' },
        }),
      });
    });
    await page.goto(WORKFLOW_URL);

    await page
      .getByRole('button', { name: 'Actions for Vectorize_Batch' })
      .click();
    await page.getByRole('menuitem', { name: 'Set breakpoint…' }).click();
    await page.getByLabel('Field').fill('payload.status');
    await page.getByLabel('Equals').fill('error');
    await page.getByRole('button', { name: 'Set', exact: true }).click();
    await expect(
      page.getByRole('button', { name: 'Set', exact: true })
    ).toBeHidden();

    // The server pushes a new snapshot after the change; a reload stands in for it here.
    await page.reload();
    await expect(
      page.getByRole('img', { name: 'Breakpoint: payload.status = error' })
    ).toBeVisible();

    await page
      .getByRole('button', { name: 'Actions for Vectorize_Batch' })
      .click();
    await page.getByRole('menuitem', { name: 'Clear breakpoint' }).click();
    await page.reload();
    await expect(page.getByRole('img', { name: /Breakpoint/ })).toBeHidden();
    expect(calls).toEqual([
      'POST ?field=payload.status&equals=error',
      'DELETE ',
    ]);
  });

  test('raises the priority of a queued run', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page, {
      progress: { ...defaultProgress, status: 'QUEUED' },
    });
    const calls: string[] = [];
    await page.route('**/api/executions/*/priority*', (route) => {
      calls.push(new URL(route.request().url()).search);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: '2026-07-26T14:23:23',
          status: 200,
          message: 'OK',
          path: '/priority',
          data: { executionId: 'exec-091-qp-55' },
        }),
      });
    });
    await page.goto(WORKFLOW_URL);

    await page.getByRole('button', { name: /priority/i }).click();
    await page.getByLabel(/Priority \(/).fill('10');
    await page.getByRole('button', { name: 'Apply' }).click();

    await expect(page.getByRole('dialog')).toBeHidden();
    expect(calls).toEqual(['?priority=10']);
  });

  test('an expanded logs terminal is not drawn over by the canvas toolbar', async ({
    page,
  }) => {
    await page.goto(WORKFLOW_URL);
    const find = page.getByLabel('Find node on canvas');
    await expect(find).toBeVisible();

    await page.getByRole('button', { name: 'Maximize the logs' }).click();

    // what is on top at the middle of the Find Node box is the logs panel, not the box
    const onTop = await find.evaluate((el) => {
      const box = el.getBoundingClientRect();
      const top = document.elementFromPoint(
        box.x + box.width / 2,
        box.y + box.height / 2
      );
      return top?.closest('[data-testid="logs-panel"]') !== null;
    });
    expect(onTop).toBe(true);

    await page.getByRole('button', { name: 'Restore the logs height' }).click();
    await expect(find).toBeVisible();
  });

  test('fits the screen: no footer and no page scroll bar', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('workflow-details-page')).toBeVisible();

    await expect(page.getByRole('contentinfo')).toHaveCount(0);
    const overflow = await page.evaluate(
      () =>
        document.documentElement.scrollHeight -
        document.documentElement.clientHeight
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });

  test('approves a step that waits for a decision', async ({ page }) => {
    const envelope = (path: string, data: unknown) => ({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        timestamp: '2026-07-26T14:23:23',
        status: 200,
        message: 'OK',
        path,
        data,
      }),
    });
    await page.route('**/api/sessions/*/approvals', (route) =>
      route.fulfill(
        envelope('/approvals', [
          {
            executionId: 'exec-091-qp-55',
            workflowId: 'wf-982-xk-11',
            nodeId: 'Sink_Storage',
            name: 'release',
            since: '2026-07-26T14:20:00',
          },
        ])
      )
    );
    let decision: string | null = null;
    await page.route('**/api/executions/*/nodes/*/signal*', (route) => {
      decision = new URL(route.request().url()).searchParams.get('decision');
      return route.fulfill(
        envelope('/signal', { executionId: 'exec-091-qp-55' })
      );
    });
    await page.goto(WORKFLOW_URL);

    const strip = page.getByRole('region', { name: 'Pending approvals' });
    await expect(strip).toContainText('Sink_Storage');
    await strip.getByRole('button', { name: 'Approve' }).click();
    await expect.poll(() => decision).toBe('approve');
  });

  test('recovers an aborted run as a new run', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page, {
      progress: { ...defaultProgress, status: 'ABORTED' },
    });
    let recovered = 0;
    await page.route('**/api/executions/*/recover', (route) => {
      recovered += 1;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: '2026-07-26T14:23:23',
          status: 200,
          message: 'OK',
          path: '/recover',
          data: { executionId: 'exec-090-aa-12' },
        }),
      });
    });
    await page.goto(WORKFLOW_URL);

    await page.getByRole('button', { name: /recover/i }).click();
    await expect(page.getByRole('dialog')).toContainText(
      'Finished nodes are replayed'
    );
    await page
      .getByRole('dialog')
      .getByRole('button', { name: 'Recover' })
      .click();

    await expect(page).toHaveURL(/exec=exec-090-aa-12/);
    expect(recovered).toBe(1);
  });

  test('copies a node action as a curl command without a key', async ({
    page,
  }) => {
    // Browsers differ on clipboard permissions, so capture what the page writes instead.
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: {
          writeText: (text: string) => {
            (window as unknown as { copied: string }).copied = text;
            return Promise.resolve();
          },
        },
      });
    });
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('workflow-details-page')).toBeVisible();

    await page
      .getByRole('button', { name: 'Actions for Vectorize_Batch' })
      .click();
    await page.getByRole('menuitem', { name: /^Copy as curl/ }).focus();
    await page.keyboard.press('ArrowRight');
    await page.getByRole('menuitem', { name: 'Pause node' }).last().click();

    await expect(page.getByRole('status')).toHaveText('Copied');
    const copied = await page.evaluate(
      () => (window as unknown as { copied: string }).copied
    );
    expect(copied).toContain(
      '/api/executions/exec-091-qp-55/nodes/Vectorize_Batch/pause'
    );
    expect(copied).toContain('-H "Authorization: Bearer $YUKTA_TOKEN"');
  });

  test('downloads the visible log lines as <session>-<execution>.log', async ({
    page,
  }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('logs-body')).toContainText(
      'Initializing workflow engine...'
    );

    await page.getByLabel('Filter logs').fill('memory pressure');
    await page.getByRole('button', { name: 'More log options' }).click();
    const download = page.waitForEvent('download');
    await page
      .getByRole('menuitem', { name: 'Download visible lines (.log)' })
      .click();

    const file = await download;
    expect(file.suggestedFilename()).toBe(
      'SESSION_A92_XP_2024-exec-091-qp-55.log'
    );
    const { readFileSync } = await import('node:fs');
    const text = readFileSync(await file.path(), 'utf8');
    expect(text).toContain('High memory pressure detected on worker-node-04');
    expect(text).not.toContain('Initializing workflow engine');
  });

  test('pages and downloads the results of a finished run', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page, {
      progress: { ...defaultProgress, status: 'SUCCESS' },
    });
    await page.route('**/api/sessions/*/executions/*/results*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          executionId: 'exec-091-qp-55',
          complete: true,
          status: 'SUCCESS',
          truncated: false,
          nextCursor: 2,
          events: [0, 1].map((sequence) => ({
            kind: 'RESULT',
            nodeId: 'Persist_Results',
            sequence,
            payload: { rows: sequence },
            status: null,
            truncated: false,
            timestamp: '2026-07-26T14:22:11Z',
          })),
        }),
      })
    );
    await page.goto(WORKFLOW_URL);

    await page.getByRole('tab', { name: 'Result' }).click();
    await expect(page.getByText('Persist_Results · #1')).toBeVisible();
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download JSON' }).click();
    expect((await download).suggestedFilename()).toBe(
      'results-exec-091-qp-55.json'
    );
  });

  test('renders the never-run state', async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page, { executions: [] });
    await page.goto(WORKFLOW_URL);

    await expect(page.getByTestId('workflow-details-page')).toBeVisible();
    const status = page.getByTestId('status-chip');
    await expect(status).toContainText('Not run');
    await expect(status).toHaveAttribute('data-status', 'NOT_RUN');
    await expect(page.getByTestId('run-history-empty')).toBeVisible();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toContainText(
      'Waiting'
    );
    await expect(page.getByTestId('logs-body')).toContainText('No log output.');
  });

  test.describe('Visual Regression', () => {
    test('should match screenshot - full page light', async ({ page }) => {
      await page.goto(WORKFLOW_URL);
      await expect(
        page.getByTestId('workflow-node-Data_Ingress')
      ).toBeVisible();
      await page.evaluate(() => document.fonts.ready);

      await expect(page).toHaveScreenshot('workflow-details-full-page.webp', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });

    test('should match screenshot - full page dark', async ({ page }) => {
      await page.goto(WORKFLOW_URL);
      await expect(
        page.getByTestId('workflow-node-Data_Ingress')
      ).toBeVisible();

      const toggle = page.getByRole('button', { name: 'Toggle theme' });
      for (let i = 0; i < 3; i += 1) {
        const title = await toggle.getAttribute('title');
        if (title === 'Current: dark') break;
        await toggle.click();
      }
      await expect(toggle).toHaveAttribute('title', 'Current: dark');
      await page.evaluate(() => document.fonts.ready);

      await expect(page).toHaveScreenshot(
        'workflow-details-full-page-dark.webp',
        {
          ...screenshotOptions.full,
          animations: 'disabled',
        }
      );
    });

    test('should match screenshot - progress panel', async ({ page }) => {
      await page.goto(WORKFLOW_URL);
      await expect(page.getByTestId('progress-panel')).toBeVisible();
      await page.evaluate(() => document.fonts.ready);

      await expect(page.getByTestId('progress-panel')).toHaveScreenshot(
        'workflow-details-progress-panel.webp',
        screenshotOptions.partial
      );
    });
  });
});
