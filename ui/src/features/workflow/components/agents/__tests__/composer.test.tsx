// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { ApiError } from '@/lib/apiClient';
import { createConversation } from '@/test/factories/conversationFactory';
import { isForbidden, steerErrorMessage } from '../../../lib/steerErrors';
import { AgentComposer } from '../AgentComposer';
import { OpenRequest } from '../OpenRequest';
import { AgentList } from '../AgentList';

const posts: { url: string; body: unknown }[] = [];
let failure: { status: number; message: string } | null = null;
let inputFails = false;

const ok = (data: unknown) =>
  HttpResponse.json({ status: 200, message: 'ok', data });

const server = setupServer(
  http.post('/api/conversations/:id/:action', async ({ request, params }) => {
    const text = await request.text();
    posts.push({
      url: `${String(params.id)}/${String(params.action)}`,
      body: text ? JSON.parse(text) : null,
    });
    if (failure) {
      return HttpResponse.json(
        { message: failure.message },
        { status: failure.status }
      );
    }
    return ok({ queued: false });
  }),
  http.get('/api/executions/e1/inputs/r1', () =>
    inputFails
      ? HttpResponse.json({ message: 'down' }, { status: 500 })
      : ok({
          id: 'r1',
          sessionId: 's1',
          workflowId: 'w1',
          executionId: 'e1',
          nodeId: 'review',
          state: 'OPEN',
          version: 1,
          kind: 'APPROVAL',
          prompt: 'Run rm -rf?',
          context: {},
          options: [],
          responseSchema: null,
          risk: 'HIGH',
          tier: 'operator',
          advice: null,
          deadline: null,
          createdAt: 't',
          updatedAt: 't',
          answer: null,
        })
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  posts.length = 0;
  failure = null;
  inputFails = false;
});
afterAll(() => server.close());

const chat = (over = {}) =>
  createConversation({
    capabilities: ['message', 'interrupt'],
    state: 'IDLE',
    ...over,
  });

describe('AgentComposer', () => {
  it('sends on Enter, not on Shift+Enter, and clears the box', async () => {
    renderWithProviders(<AgentComposer conversation={chat()} />);
    const box = screen.getByRole('textbox', { name: 'Message to review' });

    await userEvent.type(box, 'one{Shift>}{Enter}{/Shift}two');
    expect(posts).toEqual([]);
    expect(box).toHaveValue('one\ntwo');
    await userEvent.type(box, '{Enter}');

    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toEqual({
      url: 'c1/messages',
      body: { text: 'one\ntwo', interrupt: false },
    });
    await waitFor(() => expect(box).toHaveValue(''));
  });

  it('sends with the button too', async () => {
    renderWithProviders(<AgentComposer conversation={chat()} />);

    await userEvent.type(screen.getByRole('textbox'), 'hello');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0].body).toEqual({ text: 'hello', interrupt: false });
  });

  it('sends nothing for an empty message', async () => {
    renderWithProviders(<AgentComposer conversation={chat()} />);
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
    await userEvent.type(screen.getByRole('textbox'), '   {Enter}');
    expect(posts).toEqual([]);
  });

  it('offers to queue or to interrupt and send while the agent works', async () => {
    renderWithProviders(
      <AgentComposer conversation={chat({ state: 'RUNNING' })} />
    );
    await userEvent.type(screen.getByRole('textbox'), 'stop that');

    expect(screen.getByRole('button', { name: 'Queue' })).toBeEnabled();
    await userEvent.click(
      screen.getByRole('button', { name: 'Interrupt and send' })
    );

    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0].body).toEqual({ text: 'stop that', interrupt: true });
  });

  it('just sends when the agent takes a message during its turn, and cannot be interrupted without the capability', () => {
    renderWithProviders(
      <AgentComposer
        conversation={chat({
          state: 'RUNNING',
          capabilities: ['message', 'steer'],
        })}
      />
    );

    expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Interrupt and send' })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Interrupt' })
    ).not.toBeInTheDocument();
  });

  it('interrupts and ends', async () => {
    renderWithProviders(
      <AgentComposer conversation={chat({ state: 'RUNNING' })} />
    );

    await userEvent.click(screen.getByRole('button', { name: 'Interrupt' }));
    await userEvent.click(screen.getByRole('button', { name: 'End' }));

    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts.map((p) => p.url)).toEqual(['c1/interrupt', 'c1/end']);
  });

  it('is disabled, with the reason, when it cannot send, and sends no request', async () => {
    const { unmount } = renderWithProviders(
      <AgentComposer
        conversation={chat({
          state: 'ENDED',
          outcome: 'success',
          endedAt: 't',
        })}
      />
    );
    expect(screen.getByRole('textbox')).toBeDisabled();
    expect(
      screen.getByPlaceholderText('This conversation has ended.')
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'End' })
    ).not.toBeInTheDocument();
    unmount();

    renderWithProviders(
      <AgentComposer conversation={chat({ capabilities: [] })} />
    );
    expect(
      screen.getByPlaceholderText('This agent takes no messages.')
    ).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Send' }), {
      pointerEventsCheck: 0,
    });
    expect(posts).toEqual([]);
  });

  it.each([
    [409, 'The agent is not running any more.'],
    [413, 'The message is too long.'],
    [429, 'Too many messages to this agent: wait a moment.'],
    [404, 'This conversation is gone or not yours to see.'],
  ])('says in words what a %i means', async (status, words) => {
    failure = {
      status,
      message: status === 409 ? 'Conversation c1 is not running' : 'x',
    };
    renderWithProviders(<AgentComposer conversation={chat()} />);

    await userEvent.type(screen.getByRole('textbox'), 'hi{Enter}');

    expect(await screen.findByRole('alert')).toHaveTextContent(words);
  });

  it('stops offering to send once the server says the person may not steer', async () => {
    failure = { status: 403, message: 'no' };
    renderWithProviders(<AgentComposer conversation={chat()} />);

    await userEvent.type(screen.getByRole('textbox'), 'hi{Enter}');

    await waitFor(() => expect(screen.getByRole('textbox')).toBeDisabled());
    expect(
      screen.getByPlaceholderText('You may not steer this agent.')
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(
      'You may not steer this agent'
    );
  });

  it('reports a failed interrupt and a failed end', async () => {
    failure = { status: 500, message: 'down' };
    renderWithProviders(
      <AgentComposer conversation={chat({ state: 'RUNNING' })} />
    );

    await userEvent.click(screen.getByRole('button', { name: 'End' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('down');
  });
});

describe('steerErrorMessage', () => {
  it('explains every status and any other error', () => {
    expect(
      steerErrorMessage(new ApiError(409, 'This agent has no terminal'))
    ).toBe('This agent cannot do that.');
    expect(steerErrorMessage(new ApiError(500, 'down'))).toBe('down');
    expect(steerErrorMessage(new Error('offline'))).toBe('offline');
    expect(steerErrorMessage('x')).toBe('The request failed.');
    expect(isForbidden(new ApiError(403))).toBe(true);
    expect(isForbidden(new Error('x'))).toBe(false);
  });
});

describe('OpenRequest', () => {
  it('shows the question as the input row, to answer in place', async () => {
    renderWithProviders(
      <OpenRequest executionId="e1" requestId="r1" prompt="Run rm -rf?" />
    );
    expect(screen.getByText(/Request \(open\)/)).toBeInTheDocument();

    expect(
      await screen.findByRole('list', { name: 'Request the agent waits on' })
    ).toBeInTheDocument();
    expect(screen.getByText('HIGH risk')).toBeInTheDocument();
  });

  it('keeps the prompt as text when the request cannot be read', async () => {
    inputFails = true;
    renderWithProviders(
      <OpenRequest executionId="e1" requestId="r1" prompt="Run rm -rf?" />
    );

    await waitFor(() =>
      expect(screen.getByText(/Run rm -rf\?/)).toBeInTheDocument()
    );
    expect(
      screen.queryByRole('list', { name: 'Request the agent waits on' })
    ).not.toBeInTheDocument();
  });
});

describe('AgentList queue', () => {
  it('shows how many messages wait', () => {
    renderWithProviders(
      <AgentList
        conversations={[createConversation({ queued: 2 })]}
        selected={null}
        onSelect={() => {}}
      />
    );
    expect(screen.getByText('2 queued')).toBeInTheDocument();
  });
});
