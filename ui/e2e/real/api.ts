// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { expect, type APIRequestContext } from '@playwright/test';

/** A name no other test (or run) shares; every resource a spec creates carries it. */
export const unique = (test: string): string =>
  `e2e-${test}-${Math.random().toString(36).slice(2, 10)}`;

type Json = Record<string, unknown>;

const node = (id: string, type: string, config: Json = {}) => ({
  nodeId: id,
  type,
  config,
});

/** A session with one workflow `w`: a constant trigger, `step`, and a console terminal. */
export function sessionJson(
  id: string,
  description: string,
  step: ReturnType<typeof node>
): Json {
  return {
    sessionId: id,
    name: id,
    description: 'e2e',
    initiator: 'e2e',
    projectPath: '/tmp',
    workflows: {
      w: {
        workflowId: 'w',
        description,
        nodes: [
          node('t', 'CONSTANT_SOURCE'),
          step,
          node('e', 'CONSOLE_TERMINAL'),
        ],
        edges: [
          { source: 't', target: step.nodeId },
          { source: step.nodeId, target: 'e' },
        ],
      },
    },
  };
}

/** A shell step; the workflows of the real tier use only `sh` and `echo`. */
export const shell = (id: string, script: string) =>
  node(id, 'PROCESS_EXECUTOR', {
    command: ['sh', '-c', script],
    outputFormat: 'RAW',
  });

/** A step that waits for a human answer. */
export const ask = (id: string, prompt: string) =>
  node(id, 'ASK_INPUT', { prompt, kind: 'APPROVAL', ttlSeconds: 600 });

/**
 * Seeds and cleans up through REST, so a spec's setup does not depend on the screens it tests. Everything it
 * creates is deleted by `cleanup()`; a spec calls that in afterEach/afterAll.
 */
export class RealApi {
  private readonly sessions: string[] = [];
  private readonly supervisors: string[] = [];

  constructor(private readonly request: APIRequestContext) {}

  private async data<T = Json>(
    response: Awaited<ReturnType<APIRequestContext['get']>>
  ): Promise<T> {
    expect(response.ok(), await response.text()).toBeTruthy();
    const text = await response.text();
    return (text ? (JSON.parse(text) as { data: T }).data : undefined) as T;
  }

  async applySession(session: Json): Promise<string> {
    const id = session['sessionId'] as string;
    await this.data(
      await this.request.post('/api/sessions', { data: session })
    );
    this.sessions.push(id);
    return id;
  }

  /** Deletes a session the UI created, at the end of the test. */
  track(session: string): void {
    this.sessions.push(session);
  }

  trackSupervisor(id: string): void {
    this.supervisors.push(id);
  }

  async startWorkflow(session: string, payload: Json = {}): Promise<string> {
    const started = await this.data<{ executionId: string }>(
      await this.request.post('/api/workflow/start', {
        data: { sessionId: session, workflowId: 'w', payload },
      })
    );
    return started.executionId;
  }

  async execution(
    id: string
  ): Promise<{ status: string; controlState?: string }> {
    return this.data(await this.request.get(`/api/executions/${id}`));
  }

  async post(path: string): Promise<void> {
    expect((await this.request.post(path)).ok(), path).toBeTruthy();
  }

  async createSupervisor(id: string): Promise<string> {
    await this.data(
      await this.request.post('/api/supervisors', {
        data: { id, type: 'rules', settings: { rules: [] } },
      })
    );
    this.supervisors.push(id);
    return id;
  }

  async assign(supervisor: string, session: string): Promise<void> {
    expect(
      (
        await this.request.put(`/api/supervisors/${supervisor}/assignments`, {
          data: [{ target: `session:${session}`, locked: false }],
        })
      ).ok()
    ).toBeTruthy();
  }

  /** Unassigns and deletes (force cancels the runs of) everything this helper made. */
  async cleanup(): Promise<void> {
    for (const s of this.supervisors.splice(0)) {
      await this.request.put(`/api/supervisors/${s}/assignments`, { data: [] });
      await this.request.delete(`/api/supervisors/${s}`);
    }
    for (const s of this.sessions.splice(0)) {
      await this.request.delete(`/api/sessions/${s}?force=true`);
    }
  }
}
