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

const review = createConversation({
  id: 'c1',
  nodeId: 'review',
  model: 'haiku',
});
const fix = createConversation({
  id: 'c2',
  nodeId: 'fix',
  harness: 'codex',
  state: 'WAITING',
  startedAt: '2026-11-16T10:05:00Z',
});

const items: AgentItem[] = [
  {
    key: 'x/m',
    conversationId: 'gone',
    kind: 'message',
    role: 'agent',
    author: '',
    text: 'from an unknown agent',
    queued: false,
  },
  {
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
    key: 'c1/m',
    conversationId: 'c1',
    kind: 'message',
    role: 'user',
    author: 'ann',
    text: 'Please review',
    queued: true,
  },
  {
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
    key: 'c2/p',
    conversationId: 'c2',
    kind: 'plan',
    entries: [{ text: 'step', status: 'done' }],
  },
  {
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
    key: 'c2/#9',
    conversationId: 'c2',
    kind: 'result',
    status: 'failure',
    reason: 'boom',
    text: '',
  },
  {
    key: 'c2/#10',
    conversationId: 'c2',
    kind: 'notice',
    level: 'WARN',
    text: '<b>not markup</b>',
  },
  {
    key: 'c1/th',
    conversationId: 'c1',
    kind: 'message',
    role: 'thought',
    author: '',
    text: 'hmm',
    queued: false,
  },
  {
    key: 'c1/a',
    conversationId: 'c1',
    kind: 'message',
    role: 'agent',
    author: '',
    text: 'Done',
    queued: false,
  },
  {
    key: 'c1/u',
    conversationId: 'c1',
    kind: 'message',
    role: 'user',
    author: 'prompt',
    text: 'the prompt',
    queued: false,
  },
  {
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
    key: 'c2/#11',
    conversationId: 'c2',
    kind: 'result',
    status: 'success',
    reason: '',
    text: '',
  },
  {
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
    expect(screen.getByText('haiku', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('waiting')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /fix/ }));
    expect(onSelect).toHaveBeenCalledWith('c2');
    await userEvent.click(screen.getByRole('button', { name: /^All/ }));
    expect(onSelect).toHaveBeenCalledWith(null);
  });
});

describe('AgentTimeline', () => {
  useJsdomViewport();

  it('is a polite log with a node chip on each item of All', () => {
    render(
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
    render(
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
    render(
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
    render(
      <AgentTimeline items={[]} conversations={[review]} selected={null} />
    );
    expect(screen.getByText('Nothing yet.')).toBeInTheDocument();
  });

  it('shows the input and output of a tool that is opened', async () => {
    render(
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

describe('AgentsPanel', () => {
  useJsdomViewport();

  it('explains the empty state', () => {
    render(<AgentsPanel conversations={[]} items={[]} />);
    expect(
      screen.getByText(/No agent has run in this workflow yet/)
    ).toBeInTheDocument();
  });

  it('switches the timeline to the agent that is picked', async () => {
    render(<AgentsPanel conversations={[review, fix]} items={items} />);
    expect(screen.getByText(/Please review/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /fix/ }));

    expect(screen.queryByText(/Please review/)).not.toBeInTheDocument();
    expect(screen.getByText(/Result: failure/)).toBeInTheDocument();
  });
});
