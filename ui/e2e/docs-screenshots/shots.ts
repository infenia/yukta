// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * THE manifest of documentation screenshots. Add, change or remove a shot here only; the capture
 * spec, the --check mode and the how-to (docs/contributing/update-docs-screenshots.md) read this.
 * When a UI change breaks a shot, the failure names it: fix its `ready` locator or its `actions`
 * here (locators by role or visible text, never CSS paths), or its data in ./stubs/data.ts.
 */
import type { Locator, Page } from '@playwright/test';
import * as stub from './stubs/routes';
import { WORKFLOW_URL, SESSION_ID } from './stubs/data';

export interface Shot {
  /** File stem: docs/images/ui/<name>.light.png and <name>.dark.png; the UiShot `name` prop. */
  name: string;
  /** What the picture shows (also the starting point for the `alt` text). */
  shows: string;
  /** The UI route to open. */
  route: string;
  /** Stub installers, run after the base stubs. */
  stubs: stub.Stub[];
  /** Resolves when the page is drawn: a role or visible-text locator. */
  ready: (page: Page) => Locator;
  /** Steps after `ready` (open a panel, select a node). */
  actions?: (page: Page) => Promise<void>;
  /** Capture the whole scrolling page instead of the 1440x900 viewport. */
  fullPage?: boolean;
  /** Capture only this element (with its surroundings) instead of the viewport. */
  region?: (page: Page) => Locator;
  /** Docs routes that embed the shot (to know what to re-read when it changes). */
  pages: string[];
}

export const SHOTS: Shot[] = [
  {
    name: 'sessions-list',
    shows: 'The Sessions page with five sessions, their tags and latest run.',
    route: '/sessions',
    stubs: [stub.sessions],
    ready: (page) => page.getByRole('cell', { name: /Release pipeline/ }),
    pages: ['/interfaces/web-ui', '/quickstart'],
  },
  {
    name: 'workflow-canvas',
    shows:
      'A running release: deterministic steps, an AI step and a step waiting for approval.',
    route: WORKFLOW_URL,
    stubs: [stub.releaseRun],
    ready: (page) => page.getByTestId('workflow-node-risk_gate'),
    actions: async (page) => {
      await page
        .getByRole('button', { name: 'Close workflow progress panel' })
        .click();
      await page
        .getByRole('button', { name: 'Collapse execution logs' })
        .click();
      await page.getByRole('button', { name: 'Fit View' }).click();
    },
    pages: [
      '/interfaces/web-ui',
      '/concepts/ai-and-deterministic-work',
      '/concepts/nodes-edges-ports',
    ],
  },
  {
    name: 'workflow-editor',
    shows:
      'The workflow editor with the release workflow and a node open in the inspector.',
    route: `/sessions/${SESSION_ID}/workflow/release/edit`,
    stubs: [stub.editor],
    ready: (page) => page.getByRole('button', { name: 'approve ASK_INPUT' }),
    actions: async (page) => {
      await page.getByRole('button', { name: 'approve ASK_INPUT' }).click();
      await page.getByRole('heading', { name: 'approve', level: 2 }).waitFor();
    },
    pages: ['/interfaces/web-ui', '/building/workflows'],
  },
  {
    name: 'plugin-catalogue',
    shows: 'The plugin catalogue grouped by category, with one processor open.',
    route: '/plugins',
    stubs: [stub.plugins],
    ready: (page) => page.getByRole('heading', { level: 2 }).first(),
    actions: async (page) => {
      await page
        .getByRole('navigation', { name: 'Plugins' })
        .getByText('CLASSIFY')
        .first()
        .click();
      await page.getByRole('heading', { name: 'CLASSIFY', level: 2 }).waitFor();
    },
    pages: ['/interfaces/web-ui', '/plugins'],
  },
  {
    name: 'supervisors-page',
    shows:
      'The Supervisors page: the list, and the chosen supervisor with its numbers, its map and its tabs.',
    route: '/supervisors',
    stubs: [stub.supervisors],
    ready: (page) => page.getByRole('region', { name: 'Supervision map' }),
    pages: [
      '/guides/operate/use-the-web-ui',
      '/guides/supervise/use-the-control-dashboard',
    ],
  },
  {
    name: 'supervisors-catalogue',
    shows:
      'The Decision maker types sheet: the kinds of supervisor a server offers.',
    route: '/supervisors?tab=decision-makers',
    stubs: [stub.supervisors],
    ready: (page) => page.getByText('External system').first(),
    pages: [
      '/contributing/supervisors',
      '/guides/supervise/assign-a-supervisor',
    ],
  },
  {
    name: 'supervisor-details',
    shows: 'The release-guard supervisor: what it owns, and its decisions.',
    route: '/supervisors?supervisor=release-guard',
    stubs: [stub.supervisors],
    ready: (page) => page.getByRole('heading', { name: 'release-guard' }),
    pages: ['/contributing/supervisors', '/interfaces/web-ui'],
  },
  {
    name: 'supervisor-chain',
    shows:
      'The Hierarchy tab of release-guard: it reports to release-lead, and how it asks.',
    route: '/supervisors?supervisor=release-guard',
    stubs: [stub.supervisors],
    ready: (page) => page.getByRole('heading', { name: 'release-guard' }),
    actions: async (page) => {
      await page
        .getByRole('tablist', { name: 'release-guard details' })
        .getByRole('tab', { name: 'Hierarchy' })
        .click();
      await page.getByText('Reports to:').waitFor();
    },
    pages: ['/contributing/supervisors'],
  },
  {
    name: 'control-dashboard',
    shows:
      'Mission Control: run health, throughput, workers and the scheduler.',
    route: '/control',
    stubs: [stub.control, stub.pendingInput],
    fullPage: true,
    ready: (page) => page.getByTestId('command-strip'),
    actions: async (page) => {
      // charts load lazily and show a skeleton until they do
      await page
        .locator('[aria-busy="true"]')
        .first()
        .waitFor({ state: 'detached' })
        .catch(() => undefined);
    },
    pages: ['/operations', '/interfaces/web-ui'],
  },
  {
    name: 'control-overview',
    shows:
      'Mission Control above the fold: run health, throughput and capacity.',
    route: '/control',
    stubs: [stub.control, stub.pendingInput],
    ready: (page) => page.getByTestId('command-strip'),
    actions: async (page) => {
      await page
        .locator('[aria-busy="true"]')
        .first()
        .waitFor({ state: 'detached' })
        .catch(() => undefined);
    },
    pages: ['/'],
  },
  {
    name: 'inputs-panel',
    shows:
      'The Inputs panel of the running release: an approval waiting, with its approval chain.',
    route: WORKFLOW_URL,
    stubs: [stub.releaseRun, stub.pendingInput],
    ready: (page) =>
      page
        .getByRole('region', { name: 'Inputs' })
        .getByRole('button', { name: 'Approve' }),
    region: (page) => page.getByRole('region', { name: 'Inputs' }),
    pages: ['/concepts/ai-and-deterministic-work', '/interfaces/web-ui'],
  },
];
