// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  nodeAction,
  restartFromNode,
  stopNode,
  unskipNode,
  runAction,
  startWorkflow,
  stopWorkflow,
} from '../executionControl';

const calls: string[] = [];
const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const server = setupServer(
  http.post('/api/workflow/start', async ({ request }) => {
    calls.push(
      `start ${request.headers.get('Idempotency-Key')} ${JSON.stringify(await request.json())}`
    );
    return ok({ executionId: 'e1' });
  }),
  http.post('/api/workflow/:sid/:wid/stop', ({ request }) => {
    calls.push(`stop ${new URL(request.url).search}`);
    return ok({ executionIds: ['e1'] });
  }),
  http.post('/api/executions/:id/restart/:node', ({ params }) => {
    calls.push(`restart-from ${params.id} ${params.node}`);
    return ok({ executionId: 'e2' });
  }),
  http.post('/api/executions/:id/nodes/*', ({ request }) => {
    const url = new URL(request.url);
    calls.push(url.pathname + url.search);
    return ok({ executionId: 'e1' });
  }),
  http.post('/api/executions/:id/:action', ({ params }) => {
    calls.push(`${params.action} ${params.id}`);
    return ok({ executionId: 'e1' });
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  calls.length = 0;
});
afterAll(() => server.close());

describe('executionControl api', () => {
  it('starts a workflow with the payload and idempotency key', async () => {
    const result = await startWorkflow('s 1', 'wf', { a: 1 }, 'key-1');
    expect(result.executionId).toBe('e1');
    expect(calls[0]).toBe(
      'start key-1 {"sessionId":"s 1","workflowId":"wf","payload":{"a":1}}'
    );
  });

  it('stops a workflow with a mode', async () => {
    await stopWorkflow('s', 'wf', 'KILL');
    expect(calls[0]).toBe('stop ?mode=KILL');
  });

  it('runs execution actions and restarts from a node', async () => {
    await runAction('e1', 'pause');
    expect(await restartFromNode('e1', 'n 1')).toEqual({ executionId: 'e2' });
    expect(calls).toEqual(['pause e1', 'restart-from e1 n 1']);
  });

  it('stops a node with and without options', async () => {
    await stopNode('e1', 'n 1');
    await stopNode('e1', 'n1', { immediate: true, reason: 'too slow' });
    await stopNode('e1', 'n1', { immediate: false, reason: '' });
    expect(calls).toEqual([
      '/api/executions/e1/nodes/n%201/stop',
      '/api/executions/e1/nodes/n1/stop?immediate=true&reason=too+slow',
      '/api/executions/e1/nodes/n1/stop',
    ]);
  });

  it('unskips a node', async () => {
    await unskipNode('e1', 'n1');
    expect(calls).toEqual(['/api/executions/e1/nodes/n1/skip?skip=false']);
  });

  it('calls node actions including the nested step paths', async () => {
    await nodeAction('e1', 'n1', 'stop');
    await nodeAction('e1', 'n1', 'step/enable');
    expect(calls).toEqual([
      '/api/executions/e1/nodes/n1/stop',
      '/api/executions/e1/nodes/n1/step/enable',
    ]);
  });
});
