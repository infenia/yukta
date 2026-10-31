// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test as base, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { SessionListItem } from '../src/features/sessions/types/session';
import type {
  ExecutionSummary,
  LogEntry,
  WorkflowGraph,
  WorkflowProgress,
} from '../src/features/workflow/types/workflow';

export interface MockWorkflowData {
  graph?: WorkflowGraph;
  executions?: ExecutionSummary[];
  progress?: WorkflowProgress;
  logs?: LogEntry[];
}

export interface TestFixtures {
  mockSessions: SessionListItem[];
  mockSessionsApi: (
    page: Page,
    response: { status?: number; sessions?: SessionListItem[] }
  ) => Promise<void>;
  mockWorkflowApi: (page: Page, data?: MockWorkflowData) => Promise<void>;
}

const defaultSessions: SessionListItem[] = [
  {
    sessionId: 'session-1',
    name: 'Build Pipeline',
    description: 'CI/CD workflow for main branch',
    initiator: 'github-actions',
    tags: ['production', 'automated'],
    projectPath: 'infenia/yukta',
    workflowCount: 3,
  },
  {
    sessionId: 'session-2',
    name: 'Code Review',
    description: 'Quality gate for PR #42',
    initiator: 'arun@infenia.com',
    tags: ['review', 'quality-gate'],
    projectPath: 'infenia/yukta',
    workflowCount: 5,
  },
  {
    sessionId: 'session-3',
    name: 'Data Pipeline',
    description: 'Nightly ETL job',
    initiator: 'scheduler',
    tags: ['nightly'],
    projectPath: 'infenia/data-platform',
    workflowCount: 2,
  },
];

async function mockSessionsApi(
  page: Page,
  {
    status = 200,
    sessions = defaultSessions,
  }: { status?: number; sessions?: SessionListItem[] }
): Promise<void> {
  await page.route('**/api/sessions/summaries*', (route) => {
    if (status !== 200) {
      return route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: new Date().toISOString(),
          status,
          message: 'Internal server error',
          path: '/api/sessions/summaries',
          error: 'Database connection failed',
        }),
      });
    }

    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        timestamp: new Date().toISOString(),
        status: 200,
        message: 'Sessions retrieved successfully',
        path: '/api/sessions/summaries',
        data: sessions,
      }),
    });
  });
}

// Mirrors the workflow-screen design mockup: trigger fans out to two processors
// that fan into a terminal, with the first node done and the second running.
export const defaultWorkflowGraph: WorkflowGraph = {
  workflowId: 'wf-982-xk-11',
  description: 'Supply_Chain_Optimizer_V2',
  nodes: [
    {
      nodeId: 'Data_Ingress',
      type: 'ingress-v1',
      category: 'TRIGGER',
      description: 'Ingest raw data',
      uiDesign: null,
      outputPorts: ['default'],
    },
    {
      nodeId: 'Vectorize_Batch',
      type: 'vector-engine-v2',
      category: 'PROCESSOR',
      description: 'Vectorize batches',
      uiDesign: null,
      outputPorts: ['default'],
    },
    {
      nodeId: 'Anomaly_Detection',
      type: 'ml-filter-v1',
      category: 'PROCESSOR',
      description: 'Detect anomalies',
      uiDesign: null,
      outputPorts: ['default'],
    },
    {
      nodeId: 'Sink_Storage',
      type: 'storage-sink-v1',
      category: 'TERMINAL',
      description: 'Persist results',
      uiDesign: null,
      outputPorts: [],
    },
  ],
  edges: [
    { source: 'Data_Ingress', target: 'Vectorize_Batch', sourcePort: null },
    { source: 'Data_Ingress', target: 'Anomaly_Detection', sourcePort: null },
    {
      source: 'Vectorize_Batch',
      target: 'Sink_Storage',
      sourcePort: 'default',
    },
    { source: 'Anomaly_Detection', target: 'Sink_Storage', sourcePort: null },
  ],
  topologicalOrder: [
    'Data_Ingress',
    'Vectorize_Batch',
    'Anomaly_Detection',
    'Sink_Storage',
  ],
};

const defaultExecutions: ExecutionSummary[] = [
  {
    executionId: 'exec-091-qp-55',
    workflowId: 'wf-982-xk-11',
    status: 'RUNNING',
    startTime: '2026-07-26T14:22:01',
    endTime: null,
  },
  {
    executionId: 'exec-090-aa-12',
    workflowId: 'wf-982-xk-11',
    status: 'COMPLETED',
    startTime: '2026-07-25T09:10:00',
    endTime: '2026-07-25T09:14:21',
  },
];

export const defaultProgress: WorkflowProgress = {
  executionId: 'exec-091-qp-55',
  sessionId: 'SESSION_A92_XP_2024',
  workflowId: 'wf-982-xk-11',
  status: 'RUNNING',
  tasks: [
    {
      nodeId: 'Data_Ingress',
      module: 'ingress-v1',
      status: 'SUCCESS',
      startTime: '2026-07-26T14:22:01',
      endTime: '2026-07-26T14:22:10',
    },
    {
      nodeId: 'Vectorize_Batch',
      module: 'vector-engine-v2',
      status: 'RUNNING',
      startTime: '2026-07-26T14:22:11',
      endTime: null,
    },
    {
      nodeId: 'Anomaly_Detection',
      module: 'ml-filter-v1',
      status: 'PENDING',
      startTime: null,
      endTime: null,
    },
    {
      nodeId: 'Sink_Storage',
      module: 'storage-sink-v1',
      status: 'PENDING',
      startTime: null,
      endTime: null,
    },
  ],
  startTime: '2026-07-26T14:22:01',
  endTime: null,
};

const defaultLogs: LogEntry[] = [
  {
    message: 'Initializing workflow engine...',
    level: 'INFO',
    timestamp: '2026-07-26T14:22:01Z',
  },
  {
    message: 'Loading node: Data_Ingress',
    level: 'INFO',
    timestamp: '2026-07-26T14:22:03Z',
  },
  {
    message: "Connection established to S3 bucket 'raw-data-v2'",
    level: 'INFO',
    timestamp: '2026-07-26T14:22:05Z',
  },
  {
    message: 'Data_Ingress completed successfully.',
    level: 'INFO',
    timestamp: '2026-07-26T14:22:10Z',
  },
  {
    message: 'Starting node: Vectorize_Batch',
    level: 'INFO',
    timestamp: '2026-07-26T14:22:11Z',
  },
  {
    message: 'Allocating GPU resources (T4 x 2)...',
    level: 'INFO',
    timestamp: '2026-07-26T14:22:12Z',
  },
  {
    message: 'Processing batch 1/45...',
    level: 'INFO',
    timestamp: '2026-07-26T14:22:15Z',
  },
  {
    message: 'Processing batch 2/45...',
    level: 'INFO',
    timestamp: '2026-07-26T14:22:18Z',
  },
  {
    message: 'High memory pressure detected on worker-node-04',
    level: 'WARN',
    timestamp: '2026-07-26T14:22:20Z',
  },
].map((entry) => ({
  executionId: 'exec-091-qp-55',
  pluginId: 'Vectorize_Batch',
  pluginName: 'vector-engine-v2',
  stream: 'STDOUT',
  ...entry,
}));

export function sseBody(frames: unknown[]): string {
  return frames.map((frame) => `data: ${JSON.stringify(frame)}\n\n`).join('');
}

async function mockWorkflowApi(
  page: Page,
  data: MockWorkflowData = {}
): Promise<void> {
  const graph = data.graph ?? defaultWorkflowGraph;
  const executions = data.executions ?? defaultExecutions;
  const progress = data.progress ?? defaultProgress;
  const logs = data.logs ?? defaultLogs;

  const envelope = (path: string, payload: unknown) => ({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      timestamp: new Date().toISOString(),
      status: 200,
      message: 'OK',
      path,
      data: payload,
    }),
  });

  await page.route('**/api/sessions/*/workflows/*/graph', (route) =>
    route.fulfill(envelope('/graph', graph))
  );
  await page.route('**/api/sessions/*/workflows/*/executions', (route) =>
    route.fulfill(envelope('/executions', executions))
  );
  await page.route('**/api/executions/*/status/stream*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sseBody([progress]),
    })
  );
  await page.route('**/api/executions/*', (route) =>
    route.fulfill(envelope('/status', progress))
  );
  await page.route('**/api/sessions/*/executions/*/logs/entries', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sseBody(logs),
    })
  );
}

export const test = base.extend<TestFixtures>({
  mockSessions: async ({}, use) => {
    await use(defaultSessions);
  },
  mockSessionsApi: async ({}, use) => {
    await use(mockSessionsApi);
  },
  mockWorkflowApi: async ({}, use) => {
    await use(mockWorkflowApi);
  },
  page: async ({ page }, use) => {
    // An /api call that no test mocked is a test bug (it would hit no server at all), so it is
    // recorded and fails the test. Routes registered later win over this one.
    const unmocked: string[] = [];
    await page.route(/^https?:\/\/[^/]+\/api\//, (route) => {
      unmocked.push(
        `${route.request().method()} ${new URL(route.request().url()).pathname}`
      );
      return route.fulfill({ status: 501, body: 'not mocked' });
    });
    // Endpoints every page asks for. A server without them answers 404 (what the pages were
    // written for); lists answer empty. A test that needs more mocks the route itself.
    const quiet = (pattern: RegExp, status: number, data?: unknown) =>
      page.route(pattern, (route) =>
        route.request().method() !== 'GET'
          ? route.fallback()
          : route.fulfill(
              status === 200
                ? {
                    status,
                    contentType: 'application/json',
                    body: JSON.stringify({
                      timestamp: new Date().toISOString(),
                      status,
                      message: 'OK',
                      path: '/',
                      data,
                    }),
                  }
                : {
                    status,
                    contentType: 'application/json',
                    body: JSON.stringify({ message: 'not available' }),
                  }
            )
      );
    await quiet(/\/api\/(edition|me)$/, 404);
    await quiet(/\/api\/plugins$/, 200, []);
    await quiet(/\/api\/approvals(\?|$)/, 200, []);
    await quiet(/\/api\/workers$/, 200, []);
    await quiet(/\/api\/supervisors$/, 200, []);
    await quiet(/\/api\/control\/heartbeats$/, 200, []);
    await quiet(/\/api\/sessions\/[^/]+\/approvals$/, 200, []);
    await quiet(/\/api\/sessions\/[^/]+\/workflows\/[^/]+\/versions/, 200, []);
    await quiet(/\/api\/control\/audit/, 200, []);
    // The Control dashboard's endpoints, empty. mockMetricsApi (utils.ts) fills them with data.
    await quiet(
      /\/api\/metrics\/(heatmap|modules|annotations|runs\/live)(\?|$)/,
      200,
      []
    );
    await quiet(/\/api\/metrics\/snapshot(\?|$)/, 200, { time: 0, values: {} });
    await quiet(/\/api\/metrics\/series(\?|$)/, 200, {
      range: '1h',
      series: {},
    });
    await quiet(/\/api\/admin\/drain$/, 200, {
      draining: false,
      running: 0,
      queued: 0,
      idle: true,
    });
    await quiet(/\/api\/scheduler\/limits$/, 200, {
      maxConcurrent: 0,
      maxPerWorkflow: 0,
      running: 0,
      queued: 0,
      queueCapacity: 100,
    });
    await page.route(/\/api\/metrics\/stream/, (route) =>
      route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' })
    );
    await quiet(/\/api\/plugins\/[^/]+\/usage$/, 200, []);
    await quiet(/\/api\/executions(\?|$)/, 200, []);
    await quiet(/\/api\/sessions\/[^/]+\/config$/, 200, {
      sessionId: 'session',
      name: 'Session',
      description: '',
      initiator: 'ui',
      tags: {},
      projectPath: '/tmp',
      workflows: {},
    });
    await quiet(/\/api\/sessions\/[^/]+\/workflows$/, 200, []);
    await quiet(/\/api\/executions\/counts$/, 200, {
      running: 0,
      queued: 0,
      failed: 0,
    });
    await quiet(/\/api\/scheduler\/limits/, 200, {
      maxConcurrent: 0,
      maxPerWorkflow: 0,
      running: 0,
      queued: 0,
      queueCapacity: 0,
    });
    await quiet(/\/api\/sessions\/(?!summaries)[^/]+$/, 200, {
      sessionId: 'session',
      name: 'Session',
      description: '',
      initiator: 'ui',
      tags: [],
      projectPath: '/tmp',
      workflowIds: [],
    });
    await page.route(/\/api\/events\/stream/, (route) =>
      route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' })
    );
    // Every test gets the happy-path mock by default; individual tests can
    // call `mockSessionsApi(page, { ... })` again before navigating to
    // override it (e.g. empty list or a 500 response).
    await mockSessionsApi(page, {});
    await use(page);
    expect(unmocked, 'API calls without a mock').toEqual([]);
  },
});

export { expect } from '@playwright/test';
