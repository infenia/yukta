// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getSessionDraft, saveSession, validateWorkflow } from '../api';
import type { SessionDraft } from '../types';

const ok = (data: unknown) =>
  HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', data, path: '/' });

const session: SessionDraft = {
  sessionId: 's 1',
  name: 'n',
  description: 'd',
  initiator: 'i',
  tags: { env: 'prod' },
  projectPath: '/p',
  workflows: {},
};
const bodies: unknown[] = [];

const server = setupServer(
  http.get('/api/sessions/:sid/config', ({ params }) => ok({ ...session, sessionId: params.sid })),
  http.post('/api/sessions/validate', async ({ request }) => {
    bodies.push(await request.json());
    return ok({ valid: true, message: null, outputPorts: { n1: ['a'] } });
  }),
  http.post('/api/sessions', async ({ request }) => {
    bodies.push(await request.json());
    return ok(null);
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('builder api', () => {
  it('loads the full session config, keeping tag values', async () => {
    const loaded = await getSessionDraft('s 1');
    expect(loaded.sessionId).toBe('s 1');
    expect(loaded.tags).toEqual({ env: 'prod' });
  });

  it('saves the session and validates a draft workflow', async () => {
    await saveSession(session);
    const workflow = { workflowId: 'wf', description: 'd', nodes: [], edges: [] };
    const result = await validateWorkflow(workflow);

    expect(result.outputPorts).toEqual({ n1: ['a'] });
    expect(bodies).toEqual([session, workflow]);
  });
});
