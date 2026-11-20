// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';
import { ControlPage } from '../pages/ControlPage';

const ok = (data: unknown) => envelope('/api', data);

async function mockControlApi(page: Page, workers: unknown[]) {
  await page.route('**/api/supervisors/status', (route) =>
    route.fulfill(envelope('/supervisors/status', []))
  );
  await page.route('**/api/control/audit*', (route) =>
    route.fulfill(envelope('/audit', []))
  );
  await page.route('**/api/edition', (route) =>
    route.fulfill(
      envelope('/edition', {
        edition: 'PLUS',
        license: 'x',
        features: [],
        source: null,
      })
    )
  );
  await page.route('**/api/workers', (route) =>
    route.fulfill(envelope('/workers', workers))
  );
}

test.describe('Control page', () => {
  test('lists the connected workers with their capacity use', async ({
    page,
  }) => {
    await mockControlApi(page, [
      {
        workerId: 'gpu-worker-1',
        pools: ['gpu'],
        pluginTypes: ['LLM_CALL', 'HTTP_REQUEST', 'SQL_QUERY', 'MAPPER'],
        capacity: 8,
        usedSlots: 6,
        draining: false,
        registeredAt: '2026-07-26T14:00:00Z',
        leaseUntil: '2099-01-01T00:00:00Z',
      },
    ]);
    const control = new ControlPage(page);
    await control.goto();

    const row = page.getByRole('row', { name: /gpu-worker-1/ });
    await expect(row).toContainText('6/8');
    await expect(row).toContainText('+1');
    await expect(row).toContainText('gpu');
  });

  test('says when no workers are connected', async ({ page }) => {
    await mockControlApi(page, []);
    const control = new ControlPage(page);
    await control.goto();

    await expect(
      page.getByText('No workers connected', { exact: true })
    ).toBeVisible();
  });

  test('shows the live activity feed and filters it', async ({ page }) => {
    await mockControlApi(page, []);
    const frame = (type: string, severity: string, event: object) =>
      `data: ${JSON.stringify({ type, severity, rootExecutionId: 'r', depth: 0, event })}\n\n`;
    await page.route('**/api/events/stream*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body:
          frame('NodeStatusChanged', 'INFO', {
            executionId: 'exec-1111-aaaa',
            timestamp: '2026-07-26T14:22:01',
            nodeId: 'ingest',
            status: 'RUNNING',
          }) +
          frame('NodeStatusChanged', 'ERROR', {
            executionId: 'exec-2222-bbbb',
            timestamp: '2026-07-26T14:22:02',
            nodeId: 'load',
            status: 'FAILURE',
          }),
      })
    );
    const control = new ControlPage(page);
    await control.goto('activity');

    const row = (text: string) =>
      page.getByRole('row').filter({ hasText: text });
    await expect(row('ingest')).toContainText('RUNNING');
    await expect(row('load')).toContainText('FAILURE');
    await page.getByRole('button', { name: 'ERROR' }).click();
    await expect(row('ingest')).toHaveCount(0);
    await expect(row('load')).toBeVisible();
    await expect(page).toHaveURL(/severity=ERROR/);
  });

  test('shows the execution limits and changes them', async ({ page }) => {
    await mockControlApi(page, []);
    let limits = {
      maxConcurrent: 4,
      maxPerWorkflow: 0,
      running: 3,
      queued: 1,
      queueCapacity: 100,
    };
    let posted = '';
    await page.route('**/api/scheduler/limits*', (route) => {
      if (route.request().method() === 'POST') {
        posted = new URL(route.request().url()).search;
        limits = { ...limits, maxConcurrent: 8, maxPerWorkflow: 2 };
        return route.fulfill(envelope('/limits', {}));
      }
      return route.fulfill(envelope('/limits', limits));
    });
    const control = new ControlPage(page);
    await control.goto();

    await expect(page.getByText('at most unlimited')).toBeVisible();
    await page.getByRole('button', { name: 'Edit limits' }).click();
    await page.getByLabel(/Max concurrent/).fill('8');
    await page.getByLabel(/Max per workflow/).fill('2');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('button', { name: 'Confirm and save' }).click();

    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(posted).toBe('?maxConcurrent=8&maxPerWorkflow=2');
    await expect(page.getByText('of 8', { exact: true })).toBeVisible();
  });

  test('shows the dashboard: numbers, a slow live run and the limit knob', async ({
    page,
  }) => {
    await mockControlApi(page, []);
    await page.route('**/api/metrics/snapshot*', (route) =>
      route.fulfill(
        envelope('/metrics/snapshot', {
          time: Date.now(),
          values: {
            'runs.finished': 120,
            'overhead.handoff.p95': 4,
          },
        })
      )
    );
    await page.route('**/api/metrics/runs/live', (route) =>
      route.fulfill(
        envelope('/metrics/runs/live', [
          {
            executionId: 'run-1',
            sessionId: 's1',
            workflowId: 'wf-slow',
            startedAt: Date.now() - 90_000,
            elapsedMs: 90_000,
            nodesDone: 1,
            nodesSeen: 4,
            p95Ms: 30_000,
            paused: false,
            currentNodes: ['build'],
          },
        ])
      )
    );
    await page.route('**/api/metrics/heatmap*', (route) =>
      route.fulfill(
        envelope('/metrics/heatmap', [
          { time: Date.now(), runs: 10, failed: 1, failedRuns: ['x'] },
        ])
      )
    );
    let posted = '';
    await page.route('**/api/scheduler/limits*', (route) => {
      if (route.request().method() === 'POST') {
        posted = new URL(route.request().url()).search;
        return route.fulfill(envelope('/scheduler/limits', null));
      }
      return route.fulfill(
        envelope('/scheduler/limits', {
          maxConcurrent: 4,
          maxPerWorkflow: 0,
          running: 2,
          queued: 0,
          queueCapacity: 100,
        })
      );
    });
    const control = new ControlPage(page);
    await control.goto();

    const strip = page.getByTestId('command-strip');
    await expect(strip).toContainText('of 4');
    await expect(strip).toContainText('4 ms');
    await expect(strip).toContainText('90%');
    const slow = page.getByRole('listitem').filter({ hasText: 'wf-slow' });
    await expect(slow).toContainText('(slow)');
    await expect(slow).toHaveAttribute('data-slow', 'true');

    const knob = page.getByRole('slider', { name: /Concurrent run limit/ });
    await expect(knob).toHaveAttribute('aria-valuenow', '4');
    await knob.focus();
    await page.keyboard.press('ArrowUp');
    await expect.poll(() => posted).toBe('?maxConcurrent=5&maxPerWorkflow=0');
  });

  test('shows the supervisor counts and decisions, and links to a supervisor', async ({
    page,
  }) => {
    await mockControlApi(page, []);
    await page.route('**/api/supervisors/status', (route) =>
      route.fulfill(
        envelope('/supervisors/status', [
          { id: 'guard', kind: 'SUPERVISOR' },
          { id: 'bus', kind: 'CONTROL_PLANE' },
        ])
      )
    );
    await page.route('**/api/supervisors/decisions*', (route) =>
      route.fulfill(
        envelope('/decisions', [
          {
            time: new Date().toISOString(),
            supervisorId: 'guard',
            outcome: 'applied',
            command: 'PauseWorkflowCommand',
            executionId: 'run-9',
            reason: null,
          },
        ])
      )
    );
    await page.route('**/api/supervision/summary', (route) =>
      route.fulfill(
        envelope('/supervision/summary', {
          unowned: 2,
          unownedWorkflows: [{ sessionId: 's', workflowId: 'w' }],
        })
      )
    );
    const control = new ControlPage(page);
    await control.goto();

    const section = page.getByRole('region', { name: 'Supervisors' });
    await expect(section).toContainText('Direct control2');
    await expect(section).toContainText('Applied / refused1 / 0');
    await section.getByRole('link', { name: 'guard' }).click();
    await expect(page).toHaveURL(/\/supervisors\?supervisor=guard/);
  });

  test('kiosk mode fills the screen, cycles the panels and leaves on Escape', async ({
    page,
  }) => {
    await mockControlApi(page, []);
    const control = new ControlPage(page);
    await control.goto();
    await control.kioskButton.click();

    const kiosk = page.getByRole('dialog', { name: 'Kiosk mode' });
    await expect(kiosk).toBeVisible();
    const first = kiosk.getByRole('button', { name: 'Group 1' });
    await expect(first).toHaveAttribute('aria-pressed', 'true');

    await kiosk.getByRole('button', { name: 'Group 2' }).click();
    await expect(first).toHaveAttribute('aria-pressed', 'false');

    await page.keyboard.press('Escape');
    await expect(kiosk).toHaveCount(0);
  });

  test('the dashboard and kiosk do not scroll sideways on a phone', async ({
    page,
  }) => {
    await mockControlApi(page, []);
    await page.setViewportSize({ width: 390, height: 844 });
    const control = new ControlPage(page);
    await control.goto();
    const overflow = () =>
      page.evaluate(
        () =>
          document.documentElement.scrollWidth >
          document.documentElement.clientWidth
      );
    await expect(control.root).toBeVisible();
    expect(await overflow()).toBe(false);

    await control.kioskButton.click();
    const kiosk = page.getByTestId('kiosk');
    await expect(kiosk).toBeVisible();
    const sideways = await kiosk.evaluate(
      (el) => el.scrollWidth > el.clientWidth
    );
    expect(sideways).toBe(false);
  });

  test('keeps the control audit in its own tab', async ({ page }) => {
    await mockControlApi(page, []);
    const control = new ControlPage(page);
    await control.goto();
    await expect(control.root).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Control audit' })
    ).toHaveCount(0);

    await page.getByRole('tab', { name: 'Audit' }).click();
    await expect(page).toHaveURL(/tab=audit/);
    await expect(
      page.getByRole('heading', { name: 'Control audit' })
    ).toBeVisible();
    await expect(control.root).toHaveCount(0);
  });

  test('narrows every panel to a workflow chosen in the scope filter', async ({
    page,
  }) => {
    await mockControlApi(page, []);
    await page.route('**/api/sessions/summaries*', (route) =>
      route.fulfill(
        ok([
          {
            sessionId: 's1',
            name: 'Alpha',
            description: '',
            initiator: '',
            tags: [],
            projectPath: '',
            workflowCount: 1,
            lastRun: {
              executionId: 'e1',
              workflowId: 'wf-nightly',
              status: 'SUCCESS',
              startTime: null,
              endTime: null,
            },
          },
        ])
      )
    );
    const control = new ControlPage(page);
    await control.goto();

    const scope = page.getByRole('combobox', { name: 'Scope' });
    await expect(
      scope.getByRole('option', { name: 'Workflow wf-nightly' })
    ).toBeAttached();
    const heatmap = page.waitForRequest(
      (r) =>
        r.url().includes('/api/metrics/heatmap') &&
        new URL(r.url()).searchParams.get('scope') === 'workflow:wf-nightly'
    );
    const modules = page.waitForRequest(
      (r) =>
        r.url().includes('/api/metrics/modules') &&
        new URL(r.url()).searchParams.get('scope') === 'workflow:wf-nightly'
    );
    await scope.selectOption('workflow:wf-nightly');

    await expect(page).toHaveURL(/scope=workflow%3Awf-nightly/);
    await heatmap;
    await modules;
    await page.reload();
    await expect(scope).toHaveValue('workflow:wf-nightly');
  });

  test('shows the approvals waiting and the model use per provider', async ({
    page,
  }) => {
    await mockControlApi(page, []);
    const waiting = (nodeId: string) => ({
      sessionId: 's1',
      workflowId: 'wf',
      executionId: 'e1',
      nodeId,
      name: 'approve',
      since: new Date().toISOString(),
    });
    await page.route(/\/api\/inputs(\?|$)/, (route) =>
      route.fulfill(ok([waiting('gate-1'), waiting('gate-2')]))
    );
    await page.route('**/api/metrics/snapshot*', (route) =>
      route.fulfill(
        ok({
          time: Date.now(),
          values: {
            'llm.tokens{direction=input,provider=OPENAI}|rate': 2,
            'llm.tokens{direction=output,provider=OPENAI}|rate': 1,
            'llm.cost{provider=OPENAI}|rate': 0.001,
            'yukta.mcp.heavy.refused|rate': 0.1,
          },
        })
      )
    );
    const control = new ControlPage(page);
    await control.goto();

    const approvals = page.getByRole('link', {
      name: /Inputs waiting: 2\./,
    });
    await expect(approvals).toBeVisible();
    const ai = page.getByRole('region', { name: 'AI usage' });
    const openai = ai.getByRole('row', { name: /OPENAI/ });
    await expect(openai).toContainText('120');
    await expect(openai).toContainText('60');
    await expect(openai).toContainText('3.60');
    await expect(ai).toContainText('Refused heavy calls / min');
  });

  test('resumes a live run from the dashboard', async ({ page }) => {
    await mockControlApi(page, []);
    await page.route('**/api/metrics/runs/live', (route) =>
      route.fulfill(
        ok([
          {
            executionId: 'run-7',
            sessionId: 's1',
            workflowId: 'wf-held',
            startedAt: Date.now() - 5000,
            elapsedMs: 5000,
            nodesDone: 2,
            nodesSeen: 3,
            p95Ms: null,
            paused: true,
            currentNodes: [],
          },
        ])
      )
    );
    let resumed = '';
    await page.route('**/api/executions/*/resume', (route) => {
      resumed = new URL(route.request().url()).pathname;
      return route.fulfill(ok({ executionId: 'run-7' }));
    });
    const control = new ControlPage(page);
    await control.goto();

    await page
      .getByRole('button', { name: 'resume wf-held run run-7' })
      .click();
    await expect.poll(() => resumed).toBe('/api/executions/run-7/resume');
  });
});
