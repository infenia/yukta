// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TooltipProvider } from '@/components/ui/tooltip';
import { Chat } from '../components/Chat';
import * as mcp from '../lib/mcpClient';
import { resetDocs } from '../lib/docsSearch';
import { pageContext } from '../lib/pageContext';
import { call, scriptedModel, text } from '../testFixtures';
import type { ChatModel, ToolSpec } from '../types';

vi.mock('../lib/mcpClient', () => ({ listTools: vi.fn(), callTool: vi.fn() }));

const tools: ToolSpec[] = [
  { name: 'list_sessions', description: '', inputSchema: {} },
  { name: 'start_workflow', description: '', inputSchema: {} },
];
const general = pageContext('/control', '');

const show = (model: ChatModel, context = general, open = true) =>
  render(
    <TooltipProvider>
      <Chat model={model} context={context} open={open} />
    </TooltipProvider>
  );

/** Streams "Thinking", then waits until stopped. */
const stalled: ChatModel = {
  label: 'stalled',
  async *stream(_system, _history, _tools, signal) {
    yield text('Thinking');
    await new Promise((_, reject) =>
      signal.addEventListener('abort', () =>
        reject(new DOMException('stopped', 'AbortError'))
      )
    );
  },
};

beforeEach(() => {
  vi.mocked(mcp.listTools).mockReset().mockResolvedValue(tools);
  vi.mocked(mcp.callTool)
    .mockReset()
    .mockResolvedValue({ text: '{"count":2}', isError: false });
});

afterEach(() => vi.unstubAllGlobals());

describe('Chat', () => {
  it('greets with suggestions for the page and focuses the message box', () => {
    show(scriptedModel([]), pageContext('/sessions/s1/workflow/w1', ''));

    expect(screen.getByText(/Ask about your sessions/)).toBeInTheDocument();
    expect(screen.getByText('Looking at Workflow w1')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Why did the latest run fail?' })
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Message')).toHaveFocus();
  });

  it('sends a suggestion and shows the answer', async () => {
    show(scriptedModel([[text('Nothing is running.')]]));

    await userEvent.click(
      screen.getByRole('button', { name: 'What is running right now?' })
    );

    expect(await screen.findByText('Nothing is running.')).toBeInTheDocument();
    expect(screen.getByText('What is running right now?')).toBeInTheDocument();
    expect(
      screen.queryByText(/Ask about your sessions/)
    ).not.toBeInTheDocument();
  });

  it('sends on Enter, keeps Shift+Enter as a new line and ignores an empty message', async () => {
    const model = scriptedModel([[text('one')], [text('two')]]);
    show(model);
    const box = screen.getByLabelText('Message');
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();

    await userEvent.type(box, '   {Enter}');
    expect(model.sent).toHaveLength(0);
    await userEvent.clear(box);

    await userEvent.type(box, 'first{Shift>}{Enter}{/Shift}line two');
    expect(box).toHaveValue('first\nline two');
    expect(box).toHaveAttribute('rows', '2');

    await userEvent.type(box, '{Enter}');
    expect(await screen.findByText('one')).toBeInTheDocument();
    expect(box).toHaveValue('');

    await userEvent.type(box, 'again');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText('two')).toBeInTheDocument();
  });

  it('runs a read tool and shows its card', async () => {
    show(
      scriptedModel([[call('c1', 'list_sessions')], [text('Two sessions.')]])
    );

    await userEvent.type(screen.getByLabelText('Message'), 'list{Enter}');

    expect(await screen.findByText('Two sessions.')).toBeInTheDocument();
    expect(screen.getByText('list_sessions')).toBeInTheDocument();
    expect(screen.getByText('Done')).toBeInTheDocument();
  });

  it('holds a tool that changes something until the person approves it', async () => {
    show(scriptedModel([[call('c1', 'start_workflow')], [text('Started.')]]));

    await userEvent.type(screen.getByLabelText('Message'), 'start it{Enter}');
    await userEvent.click(
      await screen.findByRole('button', { name: 'Approve' })
    );

    expect(await screen.findByText('Started.')).toBeInTheDocument();
    expect(mcp.callTool).toHaveBeenCalledWith(
      'start_workflow',
      {},
      expect.anything()
    );
  });

  it('does not run a tool that is denied', async () => {
    show(
      scriptedModel([[call('c1', 'start_workflow')], [text('Left it alone.')]])
    );

    await userEvent.type(screen.getByLabelText('Message'), 'start it{Enter}');
    await userEvent.click(await screen.findByRole('button', { name: 'Deny' }));

    expect(await screen.findByText('Left it alone.')).toBeInTheDocument();
    expect(mcp.callTool).not.toHaveBeenCalled();
  });

  it('works until stopped, and a stop also declines a tool waiting for approval', async () => {
    show(stalled);
    await userEvent.type(screen.getByLabelText('Message'), 'go{Enter}');

    expect(await screen.findByText('Thinking')).toBeInTheDocument();
    // a second message is not sent while the first is being answered
    await userEvent.type(screen.getByLabelText('Message'), 'more{Enter}');
    expect(screen.getByLabelText('Message')).toHaveValue('more');

    await userEvent.click(screen.getByRole('button', { name: 'Stop' }));
    expect(
      await screen.findByRole('button', { name: 'Send' })
    ).toBeInTheDocument();
  });

  it('stops while a tool waits for approval', async () => {
    show(scriptedModel([[call('c1', 'start_workflow')]]));
    await userEvent.type(screen.getByLabelText('Message'), 'start{Enter}');
    await screen.findByRole('button', { name: 'Approve' });

    await userEvent.click(screen.getByRole('button', { name: 'Stop' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument()
    );
    expect(mcp.callTool).not.toHaveBeenCalled();
  });

  it('shows a failure of the model or of the tool list', async () => {
    show(scriptedModel([[new Error('overloaded')]]));
    await userEvent.type(screen.getByLabelText('Message'), 'a{Enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('overloaded');

    vi.mocked(mcp.listTools).mockRejectedValue(new Error('MCP down'));
    await userEvent.type(screen.getByLabelText('Message'), 'b{Enter}');
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('MCP down')
    );
  });

  it('focuses the message box when it is opened again', () => {
    const model = scriptedModel([]);
    const { rerender } = show(model, general, false);
    screen.getByLabelText('Message').blur();

    rerender(
      <TooltipProvider>
        <Chat model={model} context={general} open />
      </TooltipProvider>
    );

    expect(screen.getByLabelText('Message')).toHaveFocus();
  });

  it('looks a question up in the documentation, here in the browser', async () => {
    resetDocs();
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json([
        {
          t: 'Install',
          h: 'Docker',
          u: 'https://docs/install',
          x: 'Run docker compose to start the server.',
        },
      ])
    );
    vi.stubGlobal('fetch', fetchMock);
    show(
      scriptedModel([
        [call('c1', 'search_docs', { query: 'docker' })],
        [text('Use docker compose.')],
      ])
    );

    await userEvent.type(screen.getByLabelText('Message'), 'how do I install?{Enter}');

    expect(await screen.findByText('Use docker compose.')).toBeInTheDocument();
    expect(screen.getByText('search_docs')).toBeInTheDocument();
    expect(screen.getByText('Done')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/assistant-docs.json');
    expect(mcp.callTool).not.toHaveBeenCalled();
  });
});
