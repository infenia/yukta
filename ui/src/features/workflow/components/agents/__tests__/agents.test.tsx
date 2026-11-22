// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AgentList } from '../AgentList';
import { AgentsPanel } from '../AgentsPanel';
import { AgentTimeline } from '../AgentTimeline';
import type { AgentItem } from '../../../lib/agentItems';
import { createConversation } from '@/test/factories/conversationFactory';
import { useJsdomViewport } from '@/test/jsdomViewport';
import { createTestWrapper, renderWithProviders } from '@/test/utils/testUtils';

const AT = '2026-11-16T14:02:00Z';

const review = createConversation({
  id: 'c1',
  nodeId: 'review',
  model: 'haiku',
});
const fix = createConversation({
  id: 'c2',
  protocol: 'ACP',
  nodeId: 'fix',
  harness: 'codex',
  state: 'WAITING',
  startedAt: '2026-11-16T10:05:00Z',
});

const items: AgentItem[] = [
  {
    at: AT,
    key: 'x/m',
    conversationId: 'gone',
    kind: 'message',
    role: 'agent',
    author: '',
    text: 'from an unknown agent',
    queued: false,
  },
  {
    at: AT,
    key: 'c2/r3',
    conversationId: 'c2',
    kind: 'request',
    requestId: 'r3',
    prompt: 'Anon?',
    state: 'answered',
    decision: 'reject',
    by: '',
  },
  {
    at: AT,
    key: 'c1/m',
    conversationId: 'c1',
    kind: 'message',
    role: 'user',
    author: 'ann',
    text: 'Please review',
    queued: true,
  },
  {
    at: AT,
    key: 'c1/t',
    conversationId: 'c1',
    kind: 'tool',
    title: 'ls',
    status: 'completed',
    input: '{"a":1}',
    output: 'out',
    truncated: true,
  },
  {
    at: AT,
    key: 'c2/p',
    conversationId: 'c2',
    kind: 'plan',
    entries: [{ text: 'step', status: 'done' }],
  },
  {
    at: AT,
    key: 'c2/r',
    conversationId: 'c2',
    kind: 'request',
    requestId: 'r1',
    prompt: 'Run rm?',
    state: 'answered',
    decision: 'approve',
    by: 'key:ann',
  },
  {
    at: AT,
    key: 'c2/#9',
    conversationId: 'c2',
    kind: 'result',
    status: 'failure',
    reason: 'boom',
    text: '',
  },
  {
    at: AT,
    key: 'c2/#10',
    conversationId: 'c2',
    kind: 'notice',
    level: 'WARN',
    text: '<b>not markup</b>',
  },
  {
    at: AT,
    key: 'c1/th',
    conversationId: 'c1',
    kind: 'message',
    role: 'thought',
    author: '',
    text: 'hmm',
    queued: false,
  },
  {
    at: AT,
    key: 'c1/a',
    conversationId: 'c1',
    kind: 'message',
    role: 'agent',
    author: '',
    text: 'Done',
    queued: false,
  },
  {
    at: AT,
    key: 'c1/u',
    conversationId: 'c1',
    kind: 'message',
    role: 'user',
    author: 'prompt',
    text: 'the prompt',
    queued: false,
  },
  {
    at: AT,
    key: 'c2/r2',
    conversationId: 'c2',
    kind: 'request',
    requestId: 'r2',
    prompt: 'Open?',
    state: 'open',
    decision: '',
    by: '',
  },
  {
    at: AT,
    key: 'c2/#11',
    conversationId: 'c2',
    kind: 'result',
    status: 'success',
    reason: '',
    text: '',
  },
  {
    at: AT,
    key: 'c2/t',
    conversationId: 'c2',
    kind: 'tool',
    title: 'cat',
    status: 'running',
    input: '',
    output: '',
    truncated: false,
  },
];

describe('AgentList', () => {
  it('lists All and one row per agent, and selects on a click', async () => {
    const onSelect = vi.fn();
    render(
      <AgentList
        conversations={[review, fix]}
        selected={null}
        onSelect={onSelect}
      />
    );

    expect(screen.getByRole('button', { name: /^All/ })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(screen.getByRole('button', { name: /review/ })).toHaveTextContent(
      'claude-code · Working'
    );
    expect(screen.getByRole('button', { name: /fix/ })).toHaveTextContent(
      'codex · Waiting for you'
    );
    expect(screen.getByText('2 agents, 1 waiting')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /fix/ }));
    expect(onSelect).toHaveBeenCalledWith('c2');
    await userEvent.click(screen.getByRole('button', { name: /^All/ }));
    expect(onSelect).toHaveBeenCalledWith(null);
  });

  it('says one queued message in the singular', () => {
    render(
      <AgentList
        conversations={[createConversation({ id: 'q1', queued: 1 })]}
        selected={null}
        onSelect={vi.fn()}
      />
    );
    expect(screen.getByText('1 queued')).toBeInTheDocument();
  });
});

describe('AgentTimeline', () => {
  useJsdomViewport();

  it('is a polite log with a node chip on each item of All', () => {
    renderWithProviders(
      <AgentTimeline
        items={items}
        conversations={[review, fix]}
        selected={null}
      />
    );

    const log = screen.getByRole('log', { name: 'Agent timeline' });
    expect(log).toHaveAttribute('aria-live', 'polite');
    expect(screen.getAllByText('review').length).toBeGreaterThan(0);
    expect(screen.getByText(/Please review/)).toBeInTheDocument();
    expect(screen.getByText(/\(queued\)/)).toBeInTheDocument();
    expect(screen.getByText('gone')).toBeInTheDocument();
    expect(screen.getByText('ls')).toBeInTheDocument();
  });

  it('shows one agent without chips, and an agent text as text', () => {
    renderWithProviders(
      <AgentTimeline
        items={items}
        conversations={[review, fix]}
        selected="c2"
      />
    );

    expect(screen.queryByText('review')).not.toBeInTheDocument();
    expect(screen.getByText(/<b>not markup<\/b>/)).toBeInTheDocument();
    expect(screen.getAllByText(/Request \(answered\)/)).toHaveLength(2);
    expect(screen.getByText(/Result: failure/)).toBeInTheDocument();
    expect(screen.getByText(/approve by key:ann/)).toBeInTheDocument();
    expect(screen.getByText(/step/)).toBeInTheDocument();
  });

  it('stops following when scrolled up and follows again at the end', () => {
    renderWithProviders(
      <AgentTimeline
        items={items}
        conversations={[review, fix]}
        selected={null}
      />
    );
    const log = screen.getByRole('log');
    for (const [top, height] of [
      [0, 1000],
      [800, 1000],
    ]) {
      Object.defineProperty(log, 'scrollHeight', {
        configurable: true,
        value: height,
      });
      Object.defineProperty(log, 'scrollTop', {
        configurable: true,
        value: top,
      });
      fireEvent.scroll(log);
    }
    expect(log).toBeInTheDocument();
  });

  it('says so when there is nothing yet', () => {
    renderWithProviders(
      <AgentTimeline items={[]} conversations={[review]} selected={null} />
    );
    expect(screen.getByText('Nothing yet.')).toBeInTheDocument();
  });

  it('shows the input and output of a tool that is opened', async () => {
    renderWithProviders(
      <AgentTimeline
        items={items}
        conversations={[review, fix]}
        selected="c1"
      />
    );

    await userEvent.click(screen.getByText('ls'));

    expect(screen.getByLabelText('Input')).toHaveTextContent('{"a":1}');
    expect(screen.getByLabelText('Output')).toHaveTextContent('out');
    expect(screen.getByText('Shortened.')).toBeInTheDocument();
  });
});

describe('the look of an item', () => {
  useJsdomViewport();

  const only = (item: AgentItem, selected: string | null = 'c1') =>
    renderWithProviders(
      <AgentTimeline
        items={[item]}
        conversations={[review, fix]}
        selected={selected}
      />
    );

  it('shows the prompt of the workflow, a message you sent and a queued one', () => {
    const base = {
      at: AT,
      key: 'k',
      conversationId: 'c1',
      kind: 'message',
      role: 'user',
      queued: false,
    } as const;
    renderWithProviders(
      <AgentTimeline
        items={[
          { ...base, key: 'a', author: 'prompt', text: 'the prompt' },
          { ...base, key: 'b', author: 'ann', text: 'a hint' },
          { ...base, key: 'c', author: '', text: 'a queued one', queued: true },
        ]}
        conversations={[review]}
        selected="c1"
      />
    );

    // who wrote it is clear from the shading: no caption names the prompt or its author
    expect(
      screen.queryByText(/Prompt from the workflow/)
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/you \(ann\)/)).not.toBeInTheDocument();
    expect(screen.getByText('the prompt')).toBeInTheDocument();
    expect(screen.getByText('a hint')).toBeInTheDocument();
    expect(screen.getByText('Queued')).toBeInTheDocument();
    expect(screen.getByText(/a queued one/)).toBeInTheDocument();
  });

  it('folds a long message and opens it again with its toggle', async () => {
    const user = userEvent.setup();
    const long = 'x'.repeat(600);
    only({
      at: AT,
      key: 'long',
      conversationId: 'c1',
      kind: 'message',
      role: 'user',
      author: 'prompt',
      text: long,
      queued: false,
    });
    const more = screen.getByRole('button', { name: 'Show more' });
    await user.click(more);
    expect(
      screen.getByRole('button', { name: 'Show less' })
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Show less' }));
    expect(
      screen.getByRole('button', { name: 'Show more' })
    ).toBeInTheDocument();
  });

  it('folds a message of many lines behind a toggle', () => {
    only({
      at: AT,
      key: 'lines',
      conversationId: 'c1',
      kind: 'message',
      role: 'user',
      author: 'prompt',
      text: Array.from({ length: 9 }, (_, i) => `line ${i}`).join('\n'),
      queued: false,
    });
    expect(
      screen.getByRole('button', { name: 'Show more' })
    ).toBeInTheDocument();
  });

  it('shows what the agent was thinking, quietly', () => {
    only({
      at: AT,
      key: 't',
      conversationId: 'c1',
      kind: 'message',
      role: 'thought',
      author: '',
      text: 'hmm',
      queued: false,
    });
    expect(screen.getByText('hmm')).toHaveClass('italic');
  });

  it('summarises a request that waits when all agents are shown, and answers it in the agent', () => {
    const open: AgentItem = {
      at: AT,
      key: 'r',
      conversationId: 'c2',
      kind: 'request',
      requestId: 'r1',
      prompt: 'Run rm?',
      state: 'open',
      decision: '',
      by: '',
    };
    const { unmount } = only(open, null);
    expect(screen.getByText('Waiting for you')).toBeInTheDocument();
    expect(screen.getByText(/Open the agent to answer/)).toBeInTheDocument();
    unmount();

    only({ ...open, conversationId: 'gone' }, 'gone');
    expect(screen.getByText('Waiting for you')).toBeInTheDocument();
  });

  it('folds the tool calls of one agent in a row into one line', () => {
    const tool = (
      key: string,
      title: string,
      status: string,
      conversationId = 'c1'
    ): AgentItem => ({
      at: AT,
      key,
      conversationId,
      kind: 'tool',
      title,
      status,
      input: '',
      output: '',
      truncated: false,
    });
    renderWithProviders(
      <AgentTimeline
        items={[
          tool('a', 'ls', 'completed'),
          tool('b', 'ls', 'completed'),
          tool('c', 'grep', 'failed'),
          tool('d', 'read', 'running'),
          tool('e', 'other', 'completed', 'c2'),
        ]}
        conversations={[review, fix]}
        selected={null}
      />
    );

    expect(screen.getByText('4 tool calls')).toBeInTheDocument();
    expect(screen.getByText('ls ×2, grep, read')).toBeInTheDocument();
    expect(screen.getByText('1 failed')).toBeInTheDocument();
    expect(screen.getByText('other')).toBeInTheDocument();
  });

  it('shows a group of tool calls that all completed as completed', () => {
    renderWithProviders(
      <AgentTimeline
        items={[
          {
            at: AT,
            key: 'g1',
            conversationId: 'c1',
            kind: 'tool',
            title: 'ls',
            status: 'completed',
            input: '',
            output: '',
            truncated: false,
          },
          {
            at: AT,
            key: 'g2',
            conversationId: 'c1',
            kind: 'tool',
            title: 'cat',
            status: 'done',
            input: '',
            output: '',
            truncated: false,
          },
        ]}
        conversations={[review]}
        selected="c1"
      />
    );

    expect(screen.getByText('2 tool calls')).toBeInTheDocument();
    expect(screen.getByText('ls, cat')).toBeInTheDocument();
    expect(screen.queryByText(/failed$/)).not.toBeInTheDocument();
  });

  it('shows a group with a tool still running, and none failed, as running', () => {
    renderWithProviders(
      <AgentTimeline
        items={[
          {
            at: AT,
            key: 'r1',
            conversationId: 'c1',
            kind: 'tool',
            title: 'ls',
            status: 'completed',
            input: '',
            output: '',
            truncated: false,
          },
          {
            at: AT,
            key: 'r2',
            conversationId: 'c1',
            kind: 'tool',
            title: 'cat',
            status: 'running',
            input: '',
            output: '',
            truncated: false,
          },
        ]}
        conversations={[review]}
        selected="c1"
      />
    );

    expect(screen.getByText('2 tool calls')).toBeInTheDocument();
    expect(screen.queryByText(/failed$/)).not.toBeInTheDocument();
    // the group and each running call say it
    expect(screen.getAllByText('running')).toHaveLength(3);
  });

  it('colours a tool by how it went', () => {
    for (const status of ['done', 'failed', 'whatever']) {
      const { unmount } = only({
        at: AT,
        key: status,
        conversationId: 'c1',
        kind: 'tool',
        title: 'ls',
        status,
        input: '',
        output: '',
        truncated: false,
      });
      expect(screen.getByText(status)).toBeInTheDocument();
      unmount();
    }
  });
});

describe('AgentsPanel focus', () => {
  useJsdomViewport();

  const panel = (focus: { id: string; nonce: number } | null) => (
    <AgentsPanel conversations={[review, fix]} items={items} focus={focus} />
  );

  it('selects the conversation a click on its node asked for, once', async () => {
    const { rerender } = render(panel(null), { wrapper: createTestWrapper() });
    expect(screen.getByText('All agents of this run')).toBeInTheDocument();

    rerender(panel({ id: 'c2', nonce: 1 }));
    expect(screen.getByRole('heading', { name: 'fix' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /^All/ }));
    rerender(panel({ id: 'c2', nonce: 1 }));
    expect(screen.getByText('All agents of this run')).toBeInTheDocument();

    rerender(panel({ id: 'c1', nonce: 2 }));
    expect(screen.getByRole('heading', { name: 'review' })).toBeInTheDocument();
  });
});

describe('AgentsPanel', () => {
  useJsdomViewport();

  it('explains the empty state', () => {
    renderWithProviders(<AgentsPanel conversations={[]} items={[]} />);
    expect(screen.getByText('No agents in this run')).toBeInTheDocument();
  });

  it('titles the timeline with the agent, its kind and what it can do', async () => {
    renderWithProviders(
      <AgentsPanel conversations={[review, fix]} items={items} />
    );
    expect(screen.getByText('All agents of this run')).toBeInTheDocument();
    expect(
      screen.getByRole('textbox', { name: 'Message to the agent' })
    ).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: /review/ }));

    expect(screen.getByRole('heading', { name: 'review' })).toBeInTheDocument();
    expect(screen.getByText('Chat')).toBeInTheDocument();
    // what it can do is in the info popover, not as icons in the header
    expect(
      screen.queryByRole('img', { name: 'You can send it messages' })
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('textbox', { name: 'Message to review' })
    ).toBeEnabled();
  });

  it('explains an agent and what a person can do with it, from its info button', async () => {
    const quiet = createConversation({
      id: 'c3',
      nodeId: 'quiet',
      capabilities: [],
      protocol: 'PTY',
    });
    renderWithProviders(
      <AgentsPanel conversations={[review, quiet]} items={items} />
    );

    await userEvent.click(screen.getByRole('button', { name: /review/ }));
    await userEvent.click(
      screen.getByRole('button', { name: 'About this agent' })
    );
    expect(await screen.findByText('How you talk to it')).toBeInTheDocument();
    expect(screen.getByText('What you can do')).toBeInTheDocument();
    expect(screen.getByText('Messages.')).toBeInTheDocument();
    expect(
      screen.getByText(/A message conversation \(JSONL protocol\)\./)
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /quiet/ }));
    await userEvent.click(
      screen.getByRole('button', { name: 'About this agent' })
    );
    expect(
      await screen.findByText('Nothing: this agent only reports what it does.')
    ).toBeInTheDocument();
    expect(
      screen.getByText(/An interactive terminal session \(PTY\)\./)
    ).toBeInTheDocument();
  });

  it('switches the timeline to the agent that is picked', async () => {
    renderWithProviders(
      <AgentsPanel conversations={[review, fix]} items={items} />
    );
    expect(screen.getByText(/Please review/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /fix/ }));

    expect(screen.queryByText(/Please review/)).not.toBeInTheDocument();
    expect(screen.getByText(/Result: failure/)).toBeInTheDocument();
  });
});
