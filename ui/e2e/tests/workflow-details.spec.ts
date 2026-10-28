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
      page.getByRole('heading', { name: 'Supply_Chain_Optimizer_V2' })
    ).toBeVisible();
    await expect(page.getByText('ID: wf-982-xk-11')).toBeVisible();
    await expect(page.getByTestId('status-chip')).toHaveText('RUNNING');

    await expect(page.getByTestId('workflow-node-Data_Ingress')).toBeVisible();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toContainText(
      'Done'
    );
    await expect(
      page.getByTestId('workflow-node-Vectorize_Batch')
    ).toContainText('Active');
    await expect(page.getByTestId('workflow-node-Sink_Storage')).toContainText(
      'Waiting'
    );

    await expect(page.getByTestId('execution-summary')).toContainText(
      'exec-091-qp-55'
    );
    await expect(page.getByTestId('execution-summary')).toContainText(
      'SESSION_A92_XP_2024'
    );
    await expect(page.getByTestId('execution-summary')).toContainText(
      '--:--:--'
    );
    await expect(page.getByTestId('task-sequence')).toContainText('COMPLETED');
    await expect(page.getByTestId('task-sequence')).toContainText(
      'module: vector-engine-v2'
    );
    await expect(page.getByTestId('task-sequence')).toContainText(
      'Dur: 1m 12s'
    );

    await expect(page.getByTestId('logs-body')).toContainText(
      'Initializing workflow engine...'
    );
    await expect(page.getByTestId('logs-body')).toContainText(
      'High memory pressure detected on worker-node-04'
    );
  });

  test('shows the workflow breadcrumb trail with the run history dropdown', async ({
    page,
  }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('workflow-details-page')).toBeVisible();

    const breadcrumbBar = page.locator('#breadcrumb-actions');
    await expect(breadcrumbBar).toContainText('Run History:');
    await expect(breadcrumbBar).toContainText('exec-091-qp-55');
    await expect(page.locator('header')).toContainText('SESSION_A92_XP_2024');
    await expect(page.locator('header')).toContainText(
      'Supply_Chain_Optimizer_V2'
    );
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
    await expect(node).toContainText('Active');
    await page.evaluate(() => {
      (window as unknown as { notReloaded: boolean }).notReloaded = true;
    });

    release();

    await expect(node).toContainText('Done');
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
    await expect(page.locator('#breadcrumb-actions')).toContainText(
      'exec-090-aa-12'
    );
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

  test('downloads the visible log lines as <session>-<execution>.log', async ({
    page,
  }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('logs-body')).toContainText(
      'Initializing workflow engine...'
    );

    await page.getByLabel('Filter logs').fill('memory pressure');
    await page.getByRole('button', { name: 'Download logs' }).click();
    const download = page.waitForEvent('download');
    await page.getByRole('menuitem', { name: 'Visible lines (.log)' }).click();

    const file = await download;
    expect(file.suggestedFilename()).toBe(
      'SESSION_A92_XP_2024-exec-091-qp-55.log'
    );
    const { readFileSync } = await import('node:fs');
    const text = readFileSync(await file.path(), 'utf8');
    expect(text).toContain('High memory pressure detected on worker-node-04');
    expect(text).not.toContain('Initializing workflow engine');
  });

  test('renders the never-run state', async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page, { executions: [] });
    await page.goto(WORKFLOW_URL);

    await expect(page.getByTestId('workflow-details-page')).toBeVisible();
    await expect(page.getByTestId('status-chip')).toHaveText('NOT RUN');
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

      await expect(page).toHaveScreenshot('workflow-details-full-page.png', {
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
        'workflow-details-full-page-dark.png',
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
        'workflow-details-progress-panel.png',
        screenshotOptions.partial
      );
    });
  });
});
