// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';

const EDITOR_URL = '/sessions/session-1/workflow/build/edit';

const envelope = (path: string, data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({
    timestamp: new Date().toISOString(),
    status: 200,
    message: 'OK',
    path,
    data,
  }),
});

const plugins: Record<string, object> = {
  OneShotTrigger: {
    type: 'OneShotTrigger',
    category: 'TRIGGER',
    description: 'Fires once when the workflow starts',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
    configSchema: [],
  },
  ShellProcessor: {
    type: 'ShellProcessor',
    category: 'PROCESSOR',
    description: 'Runs a shell command',
    usagePattern: null,
    uiDesign: null,
    outputPorts: ['success', 'failure'],
    configSchema: [
      {
        name: 'command',
        type: 'STRING',
        required: true,
        description: 'The command line to run',
        options: [],
      },
    ],
  },
  FileSink: {
    type: 'FileSink',
    category: 'TERMINAL',
    description: 'Writes output to a file',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
    configSchema: [],
  },
};

async function mockEditorApi(page: Page) {
  await page.route('**/api/plugins', (route) =>
    route.fulfill(
      envelope(
        '/api/plugins',
        Object.values(plugins).map((p) => ({
          type: (p as { type: string }).type,
          category: (p as { category: string }).category,
        }))
      )
    )
  );
  await page.route('**/api/plugins/*', (route) => {
    const type = decodeURIComponent(
      new URL(route.request().url()).pathname.split('/').pop() ?? ''
    );
    return route.fulfill(envelope(`/api/plugins/${type}`, plugins[type]));
  });
  await page.route('**/api/sessions/session-1/config', (route) =>
    route.fulfill(
      envelope('/api/sessions/session-1/config', {
        sessionId: 'session-1',
        name: 'Build Pipeline',
        description: '',
        initiator: 'ci',
        tags: {},
        projectPath: 'infenia/yukta',
        workflows: {
          build: {
            workflowId: 'build',
            description: 'Compile and test',
            nodes: [
              { nodeId: 'start', type: 'OneShotTrigger', config: {} },
              {
                nodeId: 'compile',
                type: 'ShellProcessor',
                config: { command: './gradlew build' },
              },
              { nodeId: 'out', type: 'FileSink', config: {} },
            ],
            edges: [
              { source: 'start', target: 'compile', sourcePort: null },
              { source: 'compile', target: 'out', sourcePort: 'success' },
            ],
          },
        },
      })
    )
  );
}

async function openEditor(page: Page, width: number, height: number) {
  await mockEditorApi(page);
  await page.setViewportSize({ width, height });
  await page.goto(EDITOR_URL);
  await expect(page.getByTestId('workflow-editor')).toBeVisible();
  await expect(page.getByTestId('workflow-node-compile')).toBeVisible();
}

const panels = (page: Page) => ({
  list: page.getByRole('complementary', { name: 'Nodes and edges' }),
  canvas: page.getByTestId('editor-canvas'),
  inspector: page.getByRole('complementary', { name: 'Node inspector' }),
});

test.describe('Workflow editor layout', () => {
  for (const [label, width, height] of [
    ['phone', 390, 844],
    ['tablet', 768, 1024],
  ] as const) {
    test(`stacks every panel within a ${label} screen`, async ({ page }) => {
      await openEditor(page, width, height);

      const overflow = await page.evaluate(
        () =>
          document.documentElement.scrollWidth -
          document.documentElement.clientWidth
      );
      expect(overflow).toBe(0);

      const { list, canvas, inspector } = panels(page);
      const boxes = await Promise.all(
        [list, canvas, inspector].map((panel) => panel.boundingBox())
      );
      for (const box of boxes) {
        expect(box).not.toBeNull();
        expect(box!.x).toBeGreaterThanOrEqual(0);
        expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1);
      }
      // Stacked in reading order: list, then canvas, then inspector
      expect(boxes[1]!.y).toBeGreaterThan(boxes[0]!.y);
      expect(boxes[2]!.y).toBeGreaterThan(boxes[1]!.y);
      expect(boxes[1]!.height).toBeGreaterThan(200);
    });
  }

  test('keeps the three columns side by side on a desktop', async ({ page }) => {
    await openEditor(page, 1280, 800);
    const { list, canvas, inspector } = panels(page);
    const [l, c, i] = await Promise.all(
      [list, canvas, inspector].map((panel) => panel.boundingBox())
    );
    expect(c!.x).toBeGreaterThan(l!.x + l!.width - 1);
    expect(i!.x).toBeGreaterThan(c!.x + c!.width - 1);
    expect(Math.abs(c!.y - l!.y)).toBeLessThan(2);
    expect(Math.abs(i!.y - l!.y)).toBeLessThan(2);
  });

  test('should match screenshot - phone width', async ({ page }) => {
    await openEditor(page, 390, 844);
    await page.evaluate(() => document.fonts.ready);
    await expect(page).toHaveScreenshot('workflow-editor-mobile.png', {
      fullPage: true,
      maxDiffPixels: 2000,
      threshold: 0.2,
      animations: 'disabled',
    });
  });
});
