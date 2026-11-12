// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * page.route() stubs for the documentation shots. A shot lists the installers it needs; `base`
 * runs first for every shot and answers every endpoint a page asks on load with an empty
 * success, so a missing stub shows an empty panel instead of an error banner. Calls that match
 * nothing are collected (see `unmocked`) and reported by the runner with the shot's name.
 */
import type { Page, Route } from '@playwright/test';
import {
  ANNOTATIONS,
  ASSIGNMENTS,
  DECISIONS,
  EDITION,
  EXECUTIONS,
  GRAPH,
  HEATMAP,
  LIMITS,
  LIVE_RUNS,
  LOGS,
  METRIC_VALUES,
  MODULES,
  NOW,
  PENDING_INPUT,
  PLUGINS,
  PROGRESS,
  RULES_VIEW,
  SESSIONS,
  SESSION_DRAFT,
  SESSION_ID,
  SUPERVISORS,
  SUPERVISOR_TYPES,
  WORKERS,
  supervisorTypeDetails,
} from './data';

export type Stub = (page: Page) => Promise<void>;

/** The ApiResponse envelope the UI's fetch wrapper unwraps. */
export const ok = (data: unknown, status = 200) => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({
    timestamp: NOW.toISOString(),
    status,
    message: status < 400 ? 'OK' : 'Not available',
    path: '/api',
    data,
  }),
});

const sse = (frames: unknown[]) => ({
  status: 200,
  contentType: 'text/event-stream',
  body: frames.map((f) => `data: ${JSON.stringify(f)}\n\n`).join(''),
});

/** GET answers `data`; any other method falls through to the next route. */
const get = (page: Page, pattern: RegExp, data: unknown, status = 200) =>
  page.route(pattern, (route: Route) =>
    route.request().method() === 'GET'
      ? route.fulfill(ok(data, status))
      : route.fallback()
  );

const lastSegment = (route: Route) =>
  decodeURIComponent(
    new URL(route.request().url()).pathname.split('/').pop() ?? ''
  );

/** Calls no stub answered, per page; read by the runner. */
export const unmocked = new WeakMap<Page, string[]>();

export const base: Stub = async (page) => {
  const missed: string[] = [];
  unmocked.set(page, missed);
  // Registered first, so every later (more specific) route wins over it.
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => {
    missed.push(
      `${route.request().method()} ${new URL(route.request().url()).pathname}`
    );
    return route.fulfill({ status: 501, body: 'not stubbed' });
  });
  const empty = (pattern: RegExp, data: unknown = []) =>
    get(page, pattern, data);
  // 404 is what a server without the endpoint answers; the UI is written for it
  await get(page, /\/api\/(edition|me)$/, null, 404);
  await empty(/\/api\/plugins$/);
  await empty(/\/api\/inputs(\?|$)/);
  await empty(/\/api\/workers$/);
  await empty(/\/api\/supervisors$/);
  await empty(/\/api\/supervisors\/(assignments|decisions)(\?|$)/);
  await empty(/\/api\/supervisors\/[^/]+\/assignments$/);
  await empty(/\/api\/supervisors\/status$/);
  await empty(/\/api\/supervisor-engines$/);
  await empty(/\/api\/supervision(\?|$)/, {});
  await empty(/\/api\/supervision\/ownership(\?|$)/, {
    sessionId: SESSION_ID,
    workflowId: 'release',
    defaultSupervisor: true,
    run: 'default',
    nodes: {},
  });
  await empty(/\/api\/supervision\/summary$/, {
    unowned: 0,
    unownedWorkflows: [],
  });
  await empty(/\/api\/control\/(heartbeats|audit)/);
  await empty(/\/api\/sessions\/[^/]+\/inputs(\?|$)/);
  await empty(/\/api\/sessions\/[^/]+\/workflows\/[^/]+\/versions/);
  await empty(/\/api\/metrics\/(heatmap|modules|annotations|runs\/live)(\?|$)/);
  await empty(/\/api\/metrics\/snapshot(\?|$)/, {
    time: NOW.getTime(),
    values: {},
  });
  await empty(/\/api\/metrics\/series(\?|$)/, { range: '1h', series: {} });
  await empty(/\/api\/plugins\/[^/]+\/usage$/);
  await empty(/\/api\/executions(\?|$)/);
  await empty(/\/api\/executions\/counts$/, {
    running: 0,
    queued: 0,
    failed: 0,
  });
  await empty(/\/api\/admin\/drain$/, {
    draining: false,
    running: 0,
    queued: 0,
    idle: true,
  });
  await empty(/\/api\/scheduler\/limits/, { ...LIMITS, running: 0, queued: 0 });
  for (const stream of [/\/api\/metrics\/stream/, /\/api\/events\/stream/]) {
    await page.route(stream, (route) =>
      route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' })
    );
  }
};

export const sessions: Stub = async (page) => {
  await page.route('**/api/sessions/summaries*', (route) =>
    route.fulfill(ok(SESSIONS))
  );
};

/** The release run: graph, progress, executions, logs and the session around it. */
export const releaseRun: Stub = async (page) => {
  await get(page, /\/api\/sessions\/[^/]+$/, {
    sessionId: SESSION_ID,
    name: 'Release pipeline',
    description: 'Build, check, approve and deploy a tagged release',
    initiator: 'github-actions',
    tags: ['production', 'release'],
    projectPath: 'acme/storefront',
    workflowIds: ['release', 'rollback'],
  });
  await get(page, /\/api\/sessions\/[^/]+\/workflows\/[^/]+\/graph$/, GRAPH);
  await get(
    page,
    /\/api\/sessions\/[^/]+\/workflows\/[^/]+\/executions$/,
    EXECUTIONS
  );
  await get(page, /\/api\/sessions\/[^/]+\/workflows\/[^/]+$/, {
    workflowId: GRAPH.workflowId,
    nodes: SESSION_DRAFT.workflows['release'].nodes,
  });
  await page.route('**/api/executions/*/status/stream*', (route) =>
    route.fulfill(sse([PROGRESS]))
  );
  await page.route(/\/api\/executions\/[^/]+$/, (route) =>
    route.fulfill(ok(PROGRESS))
  );
  await page.route('**/api/sessions/*/executions/*/logs/entries', (route) =>
    route.fulfill(sse(LOGS))
  );
};

/** The open approval request (the Inputs panel and the control tile). */
export const pendingInput: Stub = async (page) => {
  await page.route(/\/api\/sessions\/[^/]+\/inputs(\?|$)/, (route) =>
    route.fulfill(
      ok(
        new URL(route.request().url()).searchParams
          .get('state')
          ?.includes('OPEN')
          ? [PENDING_INPUT]
          : []
      )
    )
  );
  await get(page, /\/api\/inputs(\?|$)/, [PENDING_INPUT]);
};

export const plugins: Stub = async (page) => {
  await get(
    page,
    /\/api\/plugins$/,
    PLUGINS.map(({ type, category }) => ({ type, category }))
  );
  await page.route(/\/api\/plugins\/[^/]+$/, (route) =>
    route.fulfill(ok(PLUGINS.find((p) => p.type === lastSegment(route))))
  );
};

/** The editor loads the session config, validates on save (not used) and lists plugins. */
export const editor: Stub = async (page) => {
  await plugins(page);
  await get(page, /\/api\/sessions\/[^/]+\/config$/, SESSION_DRAFT);
};

export const supervisors: Stub = async (page) => {
  await get(page, /\/api\/supervisors$/, SUPERVISORS);
  await get(page, /\/api\/supervisors\/assignments(\?|$)/, ASSIGNMENTS);
  await page.route(/\/api\/supervisors\/([^/]+)\/assignments$/, (route) => {
    const id = route.request().url().split('/').slice(-2)[0];
    return route.fulfill(ok(ASSIGNMENTS.filter((a) => a.supervisorId === id)));
  });
  await get(page, /\/api\/supervisors\/decisions(\?|$)/, DECISIONS);
  await get(page, /\/api\/supervisors\/[^/]+\/versions$/, []);
  await get(page, /\/api\/supervisors\/[^/]+\/credential(\?|$)/, null, 404);
  await get(page, /\/api\/supervisors\/[^/]+\/engine\/rules$/, RULES_VIEW);
  await get(page, /\/api\/supervisor-engines$/, SUPERVISOR_TYPES);
  await page.route(/\/api\/supervisor-engines\/[^/?]+$/, (route) => {
    const type = SUPERVISOR_TYPES.find((t) => t.type === lastSegment(route));
    return route.fulfill(ok(type ? supervisorTypeDetails(type) : null));
  });
  await get(page, /\/api\/supervisor-engines\/[^/]+\/(usage|presets)$/, []);
};

export const control: Stub = async (page) => {
  await get(page, /\/api\/edition$/, EDITION);
  await supervisors(page);
  await get(
    page,
    /\/api\/supervisors\/status$/,
    SUPERVISORS.map(({ id }) => ({ id, kind: 'SUPERVISOR' }))
  );
  await get(page, /\/api\/supervision\/summary$/, {
    unowned: 0,
    unownedWorkflows: [],
  });
  await get(page, /\/api\/workers$/, WORKERS);
  await get(page, /\/api\/scheduler\/limits/, LIMITS);
  await get(page, /\/api\/metrics\/heatmap/, HEATMAP);
  await get(page, /\/api\/metrics\/modules/, MODULES);
  await get(page, /\/api\/metrics\/annotations/, ANNOTATIONS);
  await get(page, /\/api\/metrics\/runs\/live/, LIVE_RUNS);
  await get(page, /\/api\/sessions\/summaries/, SESSIONS);
  const now = NOW.getTime();
  await get(page, /\/api\/metrics\/snapshot(\?|$)/, {
    time: now,
    values: METRIC_VALUES,
  });
  await get(page, /\/api\/metrics\/catalog$/, {
    series: Object.keys(METRIC_VALUES).sort(),
  });
  await page.route(/\/api\/metrics\/series(\?|$)/, (route) => {
    const url = new URL(route.request().url());
    const names = (url.searchParams.get('names') ?? '').split(',');
    const series = Object.fromEntries(
      Object.entries(METRIC_VALUES)
        .filter(([key]) => names.some((name) => key.startsWith(name)))
        .map(([key, value]) => [
          key,
          Array.from({ length: 24 }, (_, i) => ({
            time: now - (23 - i) * 150_000,
            // a believable wobble around the value, deterministic
            value:
              value * (0.55 + 0.45 * ((i + 1) / 24) + 0.12 * Math.sin(i * 1.7)),
          })),
        ])
    );
    return route.fulfill(ok({ range: url.searchParams.get('range'), series }));
  });
};
