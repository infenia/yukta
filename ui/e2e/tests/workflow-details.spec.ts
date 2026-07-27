// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';

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

  test('renders the running workflow with canvas, panel and logs', async ({ page }) => {
    await page.goto(WORKFLOW_URL);

    await expect(page.getByTestId('workflow-details-page')).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Supply_Chain_Optimizer_V2' })
    ).toBeVisible();
    await expect(page.getByText('ID: wf-982-xk-11')).toBeVisible();
    await expect(page.getByTestId('status-chip')).toHaveText('RUNNING');

    await expect(page.getByTestId('workflow-node-Data_Ingress')).toBeVisible();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toContainText('Status: Done');
    await expect(page.getByTestId('workflow-node-Vectorize_Batch')).toContainText(
      'Status: Active'
    );
    await expect(page.getByTestId('workflow-node-Sink_Storage')).toContainText('Status: Waiting');

    await expect(page.getByTestId('execution-summary')).toContainText('exec-091-qp-55');
    await expect(page.getByTestId('execution-summary')).toContainText('SESSION_A92_XP_2024');
    await expect(page.getByTestId('execution-summary')).toContainText('--:--:--');
    await expect(page.getByTestId('task-sequence')).toContainText('COMPLETED');
    await expect(page.getByTestId('task-sequence')).toContainText('module: vector-engine-v2');
    await expect(page.getByTestId('task-sequence')).toContainText('Dur: 1m 12s');

    await expect(page.getByTestId('logs-body')).toContainText('Initializing workflow engine...');
    await expect(page.getByTestId('logs-body')).toContainText(
      'High memory pressure detected on worker-node-04'
    );
  });

  test('shows the workflow breadcrumb trail with the run history dropdown', async ({ page }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('workflow-details-page')).toBeVisible();

    const breadcrumbBar = page.locator('#breadcrumb-actions');
    await expect(breadcrumbBar).toContainText('Run History:');
    await expect(breadcrumbBar).toContainText('exec-091-qp-55');
    await expect(page.locator('header')).toContainText('SESSION_A92_XP_2024');
    await expect(page.locator('header')).toContainText('Supply_Chain_Optimizer_V2');
  });

  test('closes and reopens the workflow progress panel from the header', async ({ page }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('progress-panel')).toBeVisible();

    await page.getByRole('button', { name: 'Close workflow progress panel' }).click();
    await expect(page.getByTestId('progress-panel')).toBeHidden();

    await page.getByRole('button', { name: 'WORKFLOW PROGRESS' }).click();
    await expect(page.getByTestId('progress-panel')).toBeVisible();
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
    await expect(page.getByTestId('logs-body')).toContainText('Initializing workflow engine...');

    await page.getByLabel('Filter logs').fill('memory');
    await expect(page.getByTestId('logs-body')).toContainText('High memory pressure');
    await expect(page.getByTestId('logs-body')).not.toContainText('Initializing');
  });

  test('switches runs through the run history dropdown', async ({ page }) => {
    await page.goto(WORKFLOW_URL);
    await expect(page.getByTestId('workflow-details-page')).toBeVisible();

    await page.getByRole('button', { name: 'Run history' }).click();
    await page.getByText('exec-090-aa-12').click();

    await expect(page).toHaveURL(/exec=exec-090-aa-12/);
    await expect(page.locator('#breadcrumb-actions')).toContainText('exec-090-aa-12');
  });

  test('renders the never-run state', async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page, { executions: [] });
    await page.goto(WORKFLOW_URL);

    await expect(page.getByTestId('workflow-details-page')).toBeVisible();
    await expect(page.getByTestId('status-chip')).toHaveText('NOT RUN');
    await expect(page.getByTestId('run-history-empty')).toBeVisible();
    await expect(page.getByTestId('workflow-node-Data_Ingress')).toContainText('Status: Waiting');
    await expect(page.getByTestId('logs-body')).toContainText('No log output.');
  });

  test.describe('Visual Regression', () => {
    test('should match screenshot - full page light', async ({ page }) => {
      await page.goto(WORKFLOW_URL);
      await expect(page.getByTestId('workflow-node-Data_Ingress')).toBeVisible();
      await page.evaluate(() => document.fonts.ready);

      await expect(page).toHaveScreenshot('workflow-details-full-page.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });

    test('should match screenshot - full page dark', async ({ page }) => {
      await page.goto(WORKFLOW_URL);
      await expect(page.getByTestId('workflow-node-Data_Ingress')).toBeVisible();

      const toggle = page.getByRole('button', { name: 'Toggle theme' });
      for (let i = 0; i < 3; i += 1) {
        const title = await toggle.getAttribute('title');
        if (title === 'Current: dark') break;
        await toggle.click();
      }
      await expect(toggle).toHaveAttribute('title', 'Current: dark');
      await page.evaluate(() => document.fonts.ready);

      await expect(page).toHaveScreenshot('workflow-details-full-page-dark.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
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
