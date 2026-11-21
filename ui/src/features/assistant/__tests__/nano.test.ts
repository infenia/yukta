// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { downloadNano, nanoAvailability, nanoModel } from '../lib/models/nano';
import { collect, nanoSession } from '../testFixtures';
import type { Msg, ToolSpec } from '../types';

const signal = new AbortController().signal;
const tool = (
  name: string,
  inputSchema: Record<string, unknown> = {}
): ToolSpec => ({
  name,
  description: `${name} does a thing`,
  inputSchema,
});
const listSessions = tool('list_sessions', {
  type: 'object',
  properties: { limit: { type: 'number' } },
});
const ask: Msg[] = [{ role: 'user', text: 'what is running?' }];

/** Installs a fake LanguageModel whose `create` hands out the given session. */
const install = (session: ReturnType<typeof nanoSession>) => {
  const create = vi.fn().mockResolvedValue(session);
  vi.stubGlobal('LanguageModel', { create, availability: vi.fn() });
  return create;
};
const run = (history: Msg[] = ask, tools: ToolSpec[] = [listSessions]) =>
  collect(nanoModel().stream('sys', history, tools, signal));

afterEach(() => vi.unstubAllGlobals());

describe('nanoAvailability', () => {
  it('says the browser has no built-in model when the API is missing', async () => {
    expect(await nanoAvailability()).toBe('unsupported');
  });

  it('asks the browser, with the languages it will be used in', async () => {
    const availability = vi.fn().mockResolvedValue('downloadable');
    vi.stubGlobal('LanguageModel', { availability });

    expect(await nanoAvailability()).toBe('downloadable');
    expect(availability.mock.calls[0][0]).toMatchObject({
      expectedInputs: [{ type: 'text', languages: ['en'] }],
    });
  });

  it('reads a failed check as unavailable', async () => {
    vi.stubGlobal('LanguageModel', {
      availability: vi.fn().mockRejectedValue(new Error('no')),
    });

    expect(await nanoAvailability()).toBe('unavailable');
  });
});

describe('downloadNano', () => {
  it('reports the progress of the download and lets the session go', async () => {
    const session = nanoSession();
    const create = vi.fn(
      async (options: { monitor: (m: EventTarget) => void }) => {
        const monitor = new EventTarget();
        options.monitor(monitor);
        monitor.dispatchEvent(
          Object.assign(new Event('downloadprogress'), { loaded: 0.5 })
        );
        return session;
      }
    );
    vi.stubGlobal('LanguageModel', { create });
    const progress = vi.fn();

    await downloadNano(progress);

    expect(progress).toHaveBeenCalledWith(0.5);
    expect(session.destroy).toHaveBeenCalled();
  });
});

describe('nanoModel', () => {
  it('runs on the device and reports how much of its context a turn used', async () => {
    install(nanoSession());
    const model = nanoModel();
    await collect(model.stream('sys', ask, [], signal));

    expect(model.onDevice).toBe(true);
    expect(model.usage?.()).toEqual({ used: 12, total: 100 });
  });

  it('is labelled as running on this device', () => {
    expect(nanoModel().label).toBe('Gemini Nano · on this device');
  });

  it('streams an answer from the conversation, the last turn being the prompt', async () => {
    const session = nanoSession([], ['Two ', 'runs.']);
    const create = install(session);
    const history: Msg[] = [
      { role: 'user', text: 'hi' },
      { role: 'assistant', text: 'Hello', calls: [] },
      { role: 'user', text: 'and now?' },
    ];

    const events = await run(history, []);

    expect(events).toEqual([
      { type: 'text', delta: 'Two ' },
      { type: 'text', delta: 'runs.' },
    ]);
    expect(create.mock.calls[0][0].initialPrompts).toEqual([
      { role: 'system', content: 'sys' },
      { role: 'user', content: 'hi' },
      { role: 'assistant', content: 'Hello' },
    ]);
    expect(session.promptStreaming.mock.calls[0][0]).toBe('and now?');
    expect(session.clone).not.toHaveBeenCalled();
    expect(session.destroy).toHaveBeenCalled();
  });

  it('takes only what is new when a build streams the text so far', async () => {
    install(nanoSession([], ['Two', 'Two runs', 'Two runs', 'Two runs.']));

    expect(await run(ask, [])).toEqual([
      { type: 'text', delta: 'Two' },
      { type: 'text', delta: ' runs' },
      { type: 'text', delta: '.' },
    ]);
  });

  it('turns tool calls and results into turns, merging neighbours and cutting long results', async () => {
    const session = nanoSession();
    const create = install(session);
    const history: Msg[] = [
      { role: 'user', text: 'q' },
      {
        role: 'assistant',
        text: '',
        calls: [{ id: 'a', name: 'list_sessions', args: { limit: 1 } }],
      },
      {
        role: 'tool',
        id: 'a',
        name: 'list_sessions',
        result: 'x'.repeat(5000),
        isError: false,
      },
      {
        role: 'tool',
        id: 'b',
        name: 'whoami',
        result: 'denied',
        isError: true,
      },
    ];

    await run(history, []);

    const prompts = create.mock.calls[0][0].initialPrompts;
    expect(prompts[2]).toEqual({
      role: 'assistant',
      content: '(called list_sessions {"limit":1})',
    });
    expect(session.promptStreaming.mock.calls[0][0]).toBe(
      `Result of list_sessions:\n${'x'.repeat(4000)}\n\nResult of whoami (failed):\ndenied`
    );
  });

  it('shows the model only the tools it can pick from, and returns the one it picks', async () => {
    const session = nanoSession(['{"tool":"list_sessions"}', '{"limit":5}']);
    install(session);

    const events = await run(ask, [listSessions, tool('delete_session')]);

    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      type: 'call',
      call: { name: 'list_sessions', args: { limit: 5 } },
    });
    const [catalog, options] = session.probe.prompt.mock
      .calls[0] as unknown as [
      string,
      { responseConstraint: { properties: { tool: { enum: string[] } } } },
    ];
    expect(catalog).toContain('- list_sessions: list_sessions does a thing');
    expect(catalog).not.toContain('delete_session');
    expect(options.responseConstraint.properties.tool.enum).toEqual([
      'list_sessions',
      'none',
    ]);
    expect(session.probe.prompt.mock.calls[1][1]).toMatchObject({
      responseConstraint: listSessions.inputSchema,
    });
    expect(session.probe.destroy).toHaveBeenCalled();
    expect(session.promptStreaming).not.toHaveBeenCalled();
  });

  it('gives each call its own id', async () => {
    install(
      nanoSession([
        '{"tool":"list_sessions"}',
        '{}',
        '{"tool":"list_sessions"}',
        '{}',
      ])
    );

    const ids = [...(await run()), ...(await run())].map(
      (e) => (e as { call: { id: string } }).call.id
    );

    expect(new Set(ids).size).toBe(2);
  });

  it('answers in text when it picks none or a tool it was not given', async () => {
    const session = nanoSession(
      ['{"tool":"none"}', '{"tool":"made_up"}'],
      ['Nothing.']
    );
    install(session);

    expect(await run()).toEqual([{ type: 'text', delta: 'Nothing.' }]);
    expect(await run()).toEqual([{ type: 'text', delta: 'Nothing.' }]);
  });

  it('calls with no arguments when the browser cannot hold the answer to the schema', async () => {
    const session = nanoSession(['{"tool":"list_sessions"}']);
    session.probe.prompt.mockResolvedValueOnce('{"tool":"list_sessions"}');
    session.probe.prompt.mockRejectedValueOnce(new Error('NotSupportedError'));
    install(session);

    expect(await run()).toMatchObject([
      { call: { name: 'list_sessions', args: {} } },
    ]);
  });

  it('does not swallow a stop while asking for arguments', async () => {
    const stopped = new AbortController();
    const session = nanoSession(['{"tool":"list_sessions"}']);
    session.probe.prompt.mockResolvedValueOnce('{"tool":"list_sessions"}');
    session.probe.prompt.mockImplementationOnce(() => {
      stopped.abort();
      return Promise.reject(new DOMException('stopped', 'AbortError'));
    });
    install(session);

    await expect(
      collect(nanoModel().stream('sys', ask, [listSessions], stopped.signal))
    ).rejects.toThrow('stopped');
    expect(session.probe.destroy).toHaveBeenCalled();
    expect(session.destroy).toHaveBeenCalled();
  });

  it('stops asking for tools after three have run for one question', async () => {
    const session = nanoSession();
    install(session);
    const result = (id: string): Msg => ({
      role: 'tool',
      id,
      name: 'list_sessions',
      result: 'r',
      isError: false,
    });

    await run([...ask, result('1'), result('2'), result('3')]);

    expect(session.clone).not.toHaveBeenCalled();
    expect(session.promptStreaming).toHaveBeenCalled();
  });

  it('tries again with the last few turns when the conversation does not fit', async () => {
    const session = nanoSession([], ['ok']);
    const create = vi
      .fn()
      .mockRejectedValueOnce(
        Object.assign(new Error('too long'), { name: 'QuotaExceededError' })
      )
      .mockResolvedValue(session);
    vi.stubGlobal('LanguageModel', { create });
    const long: Msg[] = Array.from({ length: 8 }, (_, i) => ({
      role: i % 2 ? 'assistant' : 'user',
      text: `t${i}`,
      calls: [],
    })) as Msg[];
    long.push({ role: 'user', text: 'last' });

    expect(await run(long, [])).toEqual([{ type: 'text', delta: 'ok' }]);

    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[1][0].initialPrompts).toHaveLength(1 + 3);
  });

  it('does not hide other failures, or one that comes after text was already sent', async () => {
    vi.stubGlobal('LanguageModel', {
      create: vi.fn().mockRejectedValue(new Error('gpu lost')),
    });
    await expect(run(ask, [])).rejects.toThrow('gpu lost');

    const session = nanoSession([], ['a']);
    session.promptStreaming.mockImplementation(() => {
      let pulls = 0;
      return new ReadableStream<string>({
        pull(controller) {
          if (pulls++ === 0) {
            controller.enqueue('a');
          } else {
            controller.error(
              Object.assign(new Error('late'), { name: 'QuotaExceededError' })
            );
          }
        },
      });
    });
    install(session);
    await expect(run(ask, [])).rejects.toThrow('late');
  });

  it('starts a plain session when the browser refuses initial prompts', async () => {
    const session = nanoSession([], ['hi']);
    const refused = Object.assign(new Error('unable to create a session'), {
      name: 'NotSupportedError',
    });
    const create = vi
      .fn()
      .mockRejectedValueOnce(refused)
      .mockResolvedValue(session);
    vi.stubGlobal('LanguageModel', { create, availability: vi.fn() });

    await run(
      [
        { role: 'user', text: 'earlier' },
        { role: 'assistant', text: 'sure', calls: [] },
        { role: 'user', text: 'now' },
      ],
      []
    );

    expect(create.mock.calls[1][0]).not.toHaveProperty('initialPrompts');
    const sent = session.promptStreaming.mock.calls[0] as unknown as string[];
    expect(sent[0]).toMatch(
      /^sys\n\nUser: earlier\n\nAssistant: sure\n\nUser: now$/
    );
  });

  it('does not hide other failures to create a session', async () => {
    vi.stubGlobal('LanguageModel', {
      create: vi.fn().mockRejectedValue(new Error('boom')),
      availability: vi.fn(),
    });

    await expect(run()).rejects.toThrow('boom');
  });

  it('says in plain words when the built-in model cannot start at all', async () => {
    const refused = Object.assign(new Error('unable to create a session'), {
      name: 'NotSupportedError',
    });
    vi.stubGlobal('LanguageModel', {
      create: vi.fn().mockRejectedValue(refused),
      availability: vi.fn(),
    });

    await expect(run()).rejects.toThrow("isn't working on this device");
  });
});
