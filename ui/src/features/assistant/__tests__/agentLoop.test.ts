// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_STEPS, RESULT_LIMIT, runAgent } from '../lib/agentLoop';
import * as mcp from '../lib/mcpClient';
import { call, scriptedModel as scripted, text } from '../testFixtures';
import type { ChatModel, Msg, ToolSpec } from '../types';

vi.mock('../lib/mcpClient', () => ({ callTool: vi.fn() }));

const tools: ToolSpec[] = [
  { name: 'list_sessions', description: '', inputSchema: {} },
  { name: 'start_workflow', description: '', inputSchema: {} },
];

const run = (
  model: ChatModel,
  overrides: Partial<Parameters<typeof runAgent>[0]> = {}
) => {
  const changes: Msg[][] = [];
  const confirm = vi.fn().mockResolvedValue(true);
  const done = runAgent({
    model,
    system: 'sys',
    tools,
    history: [{ role: 'user', text: 'q' }],
    signal: new AbortController().signal,
    confirm,
    onChange: (m) => changes.push(m),
    ...overrides,
  });
  return { done, changes, confirm };
};

const last = (changes: Msg[][]) => changes[changes.length - 1];

beforeEach(() => {
  vi.mocked(mcp.callTool).mockReset();
  vi.mocked(mcp.callTool).mockResolvedValue({ text: 'ok', isError: false });
});

describe('runAgent', () => {
  it('streams a text answer into one assistant message', async () => {
    const { done, changes } = run(scripted([[text('Hel'), text('lo')]]));

    await done;

    expect(last(changes)).toEqual([
      { role: 'user', text: 'q' },
      { role: 'assistant', text: 'Hello', calls: [] },
    ]);
  });

  it('runs a read tool without asking, then answers with its result', async () => {
    const model = scripted([
      [call('c1', 'list_sessions', { a: 1 })],
      [text('Two sessions')],
    ]);
    const { done, changes, confirm } = run(model);

    await done;

    expect(mcp.callTool).toHaveBeenCalledWith(
      'list_sessions',
      { a: 1 },
      expect.anything()
    );
    expect(confirm).not.toHaveBeenCalled();
    expect(last(changes)).toEqual([
      { role: 'user', text: 'q' },
      {
        role: 'assistant',
        text: '',
        calls: [{ id: 'c1', name: 'list_sessions', args: { a: 1 } }],
      },
      {
        role: 'tool',
        id: 'c1',
        name: 'list_sessions',
        result: 'ok',
        isError: false,
      },
      { role: 'assistant', text: 'Two sessions', calls: [] },
    ]);
    expect(model.sent[1]).toHaveLength(3);
  });

  it('runs a tool that may change something only after approval', async () => {
    const { done, confirm } = run(
      scripted([[call('c1', 'start_workflow')], [text('Started')]])
    );

    await done;

    expect(confirm).toHaveBeenCalledWith(
      { id: 'c1', name: 'start_workflow', args: {} },
      tools[1]
    );
    expect(mcp.callTool).toHaveBeenCalledTimes(1);
  });

  it('does not run a tool the person declined and says so', async () => {
    const { done, changes } = run(
      scripted([[call('c1', 'start_workflow')], [text('Ok, not started')]]),
      { confirm: vi.fn().mockResolvedValue(false) }
    );

    await done;

    expect(mcp.callTool).not.toHaveBeenCalled();
    expect(last(changes)[2]).toMatchObject({
      role: 'tool',
      isError: true,
      result: 'The person declined this action.',
    });
  });

  it('answers a tool the model invented with an error, not a throw', async () => {
    const { done, changes } = run(
      scripted([[call('c1', 'nope')], [text('Sorry')]])
    );

    await done;

    expect(last(changes)[2]).toMatchObject({
      isError: true,
      result: 'There is no tool named nope.',
    });
  });

  it('reports a tool that throws, and a long result is cut', async () => {
    vi.mocked(mcp.callTool)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({
        text: 'x'.repeat(RESULT_LIMIT + 5),
        isError: false,
      });
    const { done, changes } = run(
      scripted([
        [call('c1', 'list_sessions'), call('c2', 'list_sessions')],
        [text('done')],
      ])
    );

    await done;

    const results = last(changes).filter((m) => m.role === 'tool');
    expect(results[0]).toMatchObject({ result: 'boom', isError: true });
    expect((results[1] as { result: string }).result).toContain(
      '5 more characters'
    );
  });

  it('keeps an empty turn out of what the model is sent next', async () => {
    const model = scripted([[], [text('later')]]);
    const first = run(model);
    await first.done;

    const second = run(model, { history: last(first.changes) });
    await second.done;

    expect(model.sent[1]).toEqual([{ role: 'user', text: 'q' }]);
  });

  it('stops after the most steps when the model keeps calling tools', async () => {
    const model = scripted(
      Array.from({ length: MAX_STEPS + 2 }, (_, i) => [
        call(`c${i}`, 'list_sessions'),
      ])
    );
    const { done } = run(model);

    await done;

    expect(model.sent).toHaveLength(MAX_STEPS);
  });

  it('gives a call a result and rethrows when the model fails mid-turn', async () => {
    const { done, changes } = run(
      scripted([[call('c1', 'list_sessions'), new Error('overloaded')]])
    );

    await expect(done).rejects.toThrow('overloaded');

    expect(mcp.callTool).not.toHaveBeenCalled();
    expect(last(changes)[2]).toMatchObject({
      role: 'tool',
      result: 'Interrupted before it ran.',
    });
  });

  it('ends quietly, with results for its calls, when the person stops it', async () => {
    const controller = new AbortController();
    const model: ChatModel = {
      label: 'fake',
      async *stream() {
        yield call('c1', 'list_sessions');
        controller.abort();
        throw new DOMException('aborted', 'AbortError');
      },
    };
    const { done, changes } = run(model, { signal: controller.signal });

    await done;

    expect(mcp.callTool).not.toHaveBeenCalled();
    expect(last(changes)[2]).toMatchObject({
      result: 'Interrupted before it ran.',
    });
  });
});
