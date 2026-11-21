// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  vi,
} from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  createTestQueryClient,
  renderWithProviders,
} from '@/test/utils/testUtils';
import { ApiError } from '@/lib/apiClient';
import { setMe, type Me } from '@/lib/identity';
import { getInput, listInputs, type InputRequest } from '../../api/inputs';
import { ApprovalChain, inputErrorMessage, remaining } from '../InputRow';
import type { AskingNode } from '../../lib/inputNodes';
import { InputsPanel } from '../InputsPanel';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const request = (over: Partial<InputRequest> = {}): InputRequest => ({
  id: 'r1',
  sessionId: 's1',
  workflowId: 'wf',
  executionId: 'e1',
  nodeId: 'gate',
  state: 'OPEN',
  version: 3,
  kind: 'APPROVAL',
  prompt: 'Deploy to production?',
  context: {},
  options: [],
  responseSchema: null,
  risk: 'LOW',
  tier: 'operator',
  advice: null,
  deadline: null,
  createdAt: 't',
  updatedAt: 't',
  answer: null,
  ...over,
});

let open: InputRequest[] = [];
let history: InputRequest[] = [];
let listFails = false;
let failure: { status: number; body: unknown } | null = null;
const posts: { url: string; body: Record<string, unknown> }[] = [];
const queries: string[] = [];

const server = setupServer(
  http.get('/api/sessions/s1/inputs', ({ request: req }) => {
    const url = new URL(req.url);
    queries.push(url.search);
    if (listFails) {
      return HttpResponse.json({ message: 'down' }, { status: 500 });
    }
    return ok(url.searchParams.get('state')?.includes('OPEN') ? open : history);
  }),
  http.get('/api/executions/e1/inputs/r1', () => ok(request())),
  http.post(
    '/api/executions/:e/inputs/:r/:action',
    async ({ request: req, params }) => {
      posts.push({
        url: `${String(params.e)}/${String(params.r)}/${String(params.action)}`,
        body: (await req.json()) as Record<string, unknown>,
      });
      if (failure) {
        return HttpResponse.json(failure.body, { status: failure.status });
      }
      open = [];
      return ok(request({ state: 'ANSWERED' }));
    }
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  open = [];
  history = [];
  listFails = false;
  failure = null;
  posts.length = 0;
  queries.length = 0;
  setMe(null);
  vi.useRealTimers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());

const panel = (live = true) =>
  renderWithProviders(
    <InputsPanel sessionId="s1" workflowId="wf" live={live} />
  );

const viewer = { role: 'VIEWER', permissions: { controlRuns: false } } as Me;

describe('InputsPanel', () => {
  it('says nothing is waiting while no request has been asked', async () => {
    const view = panel();
    await waitFor(() =>
      expect(queries[0]).toBe('?state=OPEN,ESCALATED&workflowId=wf&limit=200')
    );
    expect(
      await within(view.container).findByText('No input requests are waiting.')
    ).toBeInTheDocument();
  });

  it('has a history tab once a request is there', async () => {
    open = [request()];
    panel();
    await userEvent.click(await screen.findByRole('tab', { name: /^History/ }));
    expect(
      await screen.findByText(
        'Answered, expired and cancelled requests are listed here.'
      )
    ).toBeInTheDocument();
  });

  it('lists the answered, expired and cancelled requests with who answered', async () => {
    history = [
      request({
        id: 'a',
        state: 'ANSWERED',
        prompt: 'Was it ok?',
        answer: {
          decision: 'approve',
          text: 'secret',
          data: {},
          responderType: 'HUMAN',
          responderId: 'key:alice',
          rationale: null,
          confidence: null,
          answeredAt: 't',
        },
      }),
      request({ id: 'b', state: 'EXPIRED', prompt: 'Too late' }),
    ];
    panel();
    await userEvent.click(await screen.findByRole('tab', { name: /^History/ }));
    expect(await screen.findByText('Was it ok?')).toBeInTheDocument();
    expect(screen.getByText('Approved')).toBeInTheDocument();
    expect(screen.getByText(/key:alice/)).toBeInTheDocument();
    expect(screen.getByText('Too late')).toBeInTheDocument();
    expect(screen.queryByText('secret')).not.toBeInTheDocument();
  });

  it('shows why the list cannot be read', async () => {
    listFails = true;
    panel();
    expect(await screen.findByText('down')).toBeInTheDocument();
  });

  it('polls while the run is going, and not when it has ended', async () => {
    const interval = (live: boolean) => {
      const queryClient = createTestQueryClient();
      const view = renderWithProviders(
        <InputsPanel sessionId="s1" workflowId="wf" live={live} />,
        { queryClient }
      );
      const query = queryClient.getQueryCache().find({
        queryKey: ['inputs', 's1', 'wf', 'open'],
      });
      const value = query?.observers[0].options.refetchInterval;
      view.unmount();
      return value;
    };
    expect(interval(true)).toBe(5000);
    expect(interval(false)).toBe(false);
  });

  it('approves, and the row goes away', async () => {
    open = [request()];
    panel();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Approve' })
    );
    await waitFor(() =>
      expect(posts).toEqual([
        {
          url: 'e1/r1/answer',
          body: { decision: 'approve', expectedVersion: 3 },
        },
      ])
    );
    expect(
      await screen.findByText('No input requests are waiting.')
    ).toBeInTheDocument();
  });

  it('sends the chosen option, or the option an approval lists', async () => {
    open = [
      request({
        kind: 'CHOICE',
        options: [
          { id: 'blue', label: 'Blue' },
          { id: 'reject', label: 'None' },
        ],
      }),
    ];
    panel();
    await userEvent.click(await screen.findByRole('button', { name: 'Blue' }));
    await waitFor(() => expect(posts[0]?.body.decision).toBe('blue'));
  });

  it('answers a text request', async () => {
    open = [request({ kind: 'TEXT', prompt: 'Why?' })];
    panel();
    const send = await screen.findByRole('button', { name: 'Send answer' });
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Answer'), 'because');
    await userEvent.click(send);
    await waitFor(() =>
      expect(posts[0]?.body).toMatchObject({
        decision: 'answer',
        text: 'because',
      })
    );
  });

  it('parses a structured answer and shows the schema', async () => {
    open = [
      request({ kind: 'STRUCTURED', responseSchema: '{"type":"object"}' }),
    ];
    panel();
    await userEvent.click(await screen.findByText('Schema'));
    const box = screen.getByLabelText('Answer as JSON');
    await userEvent.click(box);
    await userEvent.paste('[1]');
    await userEvent.click(screen.getByRole('button', { name: 'Send answer' }));
    expect(
      await screen.findByText('The answer must be a JSON object.')
    ).toBeInTheDocument();
    await userEvent.clear(box);
    await userEvent.type(box, 'not json');
    await userEvent.click(screen.getByRole('button', { name: 'Send answer' }));
    await userEvent.clear(box);
    await userEvent.paste('{"n":1}');
    await userEvent.click(screen.getByRole('button', { name: 'Send answer' }));
    await waitFor(() => expect(posts[0]?.body.data).toEqual({ n: 1 }));
    expect(posts).toHaveLength(1);
  });

  it('asks before it answers a HIGH risk request', async () => {
    open = [request({ risk: 'HIGH' })];
    panel();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Approve' })
    );
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(posts).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: 'Confirm',
      })
    );
    await waitFor(() => expect(posts).toHaveLength(1));
  });

  it('escalates with a reason', async () => {
    open = [request()];
    panel();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Escalate' })
    );
    const dialog = screen.getByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Confirm' });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'budget');
    await userEvent.type(within(dialog).getByLabelText(/Advice/), 'ask cfo');
    await userEvent.click(confirm);
    await waitFor(() =>
      expect(posts).toEqual([
        {
          url: 'e1/r1/escalate',
          body: { reason: 'budget', advice: 'ask cfo' },
        },
      ])
    );
  });

  it('closes the escalation dialog without sending', async () => {
    open = [request()];
    panel();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Escalate' })
    );
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(posts).toEqual([]);
  });

  it('escalates without advice', async () => {
    open = [request()];
    panel();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Escalate' })
    );
    await userEvent.type(screen.getByLabelText('Reason'), 'why');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(posts[0]?.body).toEqual({ reason: 'why' }));
  });

  it('names the rule of a 403', async () => {
    open = [request()];
    failure = {
      status: 403,
      body: { message: 'no', errors: [{ field: 'rule', message: 'risk' }] },
    };
    panel();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Approve' })
    );
    expect(await screen.findByText('Not allowed: risk')).toBeInTheDocument();
  });

  it('says a 409 was already answered', async () => {
    open = [request()];
    failure = { status: 409, body: { message: 'x' } };
    panel();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Approve' })
    );
    expect(await screen.findByText(/Already answered/)).toBeInTheDocument();
  });

  it('shows any other failure as the server said it', async () => {
    open = [request()];
    failure = { status: 403, body: { message: 'plain' } };
    panel();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Approve' })
    );
    expect(await screen.findByText(/can't do this/)).toBeInTheDocument();
  });

  it('gives a viewer no actions', async () => {
    setMe(viewer);
    open = [request({ kind: 'TEXT' })];
    panel();
    expect(await screen.findByText('Deploy to production?')).toBeVisible();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByLabelText('Answer')).toBeNull();
    expect(screen.getByText(/Answering needs the OPERATOR role/)).toBeVisible();
  });

  it('renders the prompt, context and advice as text', async () => {
    open = [
      request({
        prompt: '<script>window.__x = 1</script><b>bold</b>',
        context: { html: '<img src=x>' },
        advice: '<i>careful</i>',
        risk: 'MEDIUM',
        state: 'ESCALATED',
      }),
    ];
    const { container } = panel();
    expect(
      await screen.findByText('<script>window.__x = 1</script><b>bold</b>')
    ).toBeInTheDocument();
    expect(screen.getByText(/<i>careful<\/i>/)).toBeInTheDocument();
    expect(container.querySelector('script, b, i, img')).toBeNull();
    expect(screen.getByText('Medium risk')).toBeInTheDocument();
  });

  it('folds a long prompt away, with a copy button', async () => {
    const long = 'x'.repeat(300);
    open = [request({ prompt: long })];
    panel();
    await userEvent.click(await screen.findByText('show all'));
    expect(screen.getByRole('button', { name: 'Copy prompt' })).toBeVisible();
  });

  it('counts down to the deadline, and stops for reduced motion', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    open = [request({ deadline: new Date(Date.now() + 90_000).toISOString() })];
    panel();
    expect(await screen.findByText(/1 min \d+ s left/)).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(40_000));
    expect(screen.getByText(/\d+ s left/)).toBeInTheDocument();
  });

  it('keeps the countdown still when the user prefers reduced motion', async () => {
    window.matchMedia = vi.fn().mockReturnValue({ matches: true });
    const spy = vi.spyOn(globalThis, 'setInterval');
    open = [request({ deadline: new Date(Date.now() + 90_000).toISOString() })];
    panel();
    await screen.findByText(/left/);
    expect(spy).not.toHaveBeenCalledWith(expect.any(Function), 1000);
  });
});

describe('remaining', () => {
  it('reads in the unit that fits', () => {
    const now = Date.parse('2026-01-01T00:00:00Z');
    const at = (s: number) => new Date(now + s * 1000).toISOString();
    expect(remaining(at(-1), now)).toBe('overdue');
    expect(remaining(at(30), now)).toBe('30 s left');
    expect(remaining(at(125), now)).toBe('2 min 5 s left');
    expect(remaining(at(7500), now)).toBe('2 h 5 min left');
  });
});

describe('inputErrorMessage', () => {
  it('passes on what is not an API error, and 403 and 409 without detail', () => {
    expect(inputErrorMessage(new Error('offline'))).toBe('offline');
    expect(inputErrorMessage(new ApiError(409))).toMatch(/Already answered/);
    expect(inputErrorMessage(new ApiError(403, 'no', []))).toBe('no');
  });
});

describe('listInputs', () => {
  it('asks for the states of every workflow', async () => {
    await listInputs('s1', { state: ['ANSWERED'] });
    expect(queries.at(-1)).toBe('?state=ANSWERED&limit=200');
  });
});

describe('getInput', () => {
  it('reads one request', async () => {
    expect((await getInput('e1', 'r1')).id).toBe('r1');
  });
});

describe('ApprovalChain', () => {
  const asked = [
    { who: 'guard', said: 'asked for approval', reason: null, at: 't' },
    { who: 'release-lead', said: 'no-opinion', reason: null, at: 't' },
    { who: 'build-agent', said: 'escalated', reason: 'unsure', at: 't' },
  ];

  it('says who was asked, then who is asked now', () => {
    const view = render(<ApprovalChain request={request({ asked })} you />);
    expect(screen.getByLabelText('Approval chain')).toHaveTextContent(
      'Asked guard (asked for approval), then release-lead (no opinion), then build-agent (escalated: unsure), now you.'
    );
    view.rerender(
      <ApprovalChain request={request({ asked, tier: 'leads' })} you={false} />
    );
    expect(screen.getByLabelText('Approval chain')).toHaveTextContent(
      /, now tier leads\.$/
    );
  });

  it('has no "now" once the request ended, and nothing without steps', () => {
    const view = render(
      <ApprovalChain request={request({ asked, state: 'ANSWERED' })} you />
    );
    expect(screen.getByLabelText('Approval chain')).not.toHaveTextContent(
      'now'
    );
    view.rerender(<ApprovalChain request={request({ asked: [] })} you />);
    expect(screen.queryByLabelText('Approval chain')).toBeNull();
    view.rerender(<ApprovalChain request={request()} you />);
    expect(screen.queryByLabelText('Approval chain')).toBeNull();
  });
});

const answered = (over: Partial<InputRequest> = {}) =>
  request({
    id: 'h1',
    state: 'ANSWERED',
    prompt: 'Release?',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:01:02Z',
    answer: {
      decision: 'approve',
      text: null,
      data: {},
      responderType: 'HUMAN',
      responderId: 'alice',
      rationale: null,
      confidence: null,
      answeredAt: '2026-01-01T00:01:02Z',
    },
    ...over,
  });

const ANSWER = answered().answer as NonNullable<InputRequest['answer']>;

const nodes: AskingNode[] = [
  { nodeId: 'gate', type: 'ASK_INPUT', label: 'Asks a question' },
];

describe('InputsPanel redesign', () => {
  const openHistory = async () =>
    userEvent.click(await screen.findByRole('tab', { name: /^History/ }));

  it('explains what will show up before the first run, and opens a node', async () => {
    const onNode = vi.fn();
    renderWithProviders(
      <InputsPanel
        sessionId="s1"
        workflowId="wf"
        hasRun={false}
        askingNodes={nodes}
        onNode={onNode}
      />
    );
    expect(
      await screen.findByText('This workflow asks for input')
    ).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole('button', { name: 'gate · Asks a question' })
    );
    expect(onNode).toHaveBeenCalledWith('gate');
  });

  it('lists the asking nodes as plain chips without a node opener', async () => {
    renderWithProviders(
      <InputsPanel
        sessionId="s1"
        workflowId="wf"
        hasRun={false}
        askingNodes={nodes}
      />
    );
    expect(await screen.findByText('gate · Asks a question')).toBeVisible();
    expect(screen.queryByRole('button', { name: /gate/ })).toBeNull();
  });

  it('says a live run has not asked yet', async () => {
    renderWithProviders(
      <InputsPanel sessionId="s1" workflowId="wf" askingNodes={nodes} />
    );
    expect(await screen.findByText('Nothing to answer yet')).toBeVisible();
    expect(
      screen.getByText(/as soon as one of these nodes asks/)
    ).toBeVisible();
  });

  it('says everything is answered and links to the history', async () => {
    history = [answered()];
    panel();
    expect(await screen.findByText('All caught up')).toBeVisible();
    await userEvent.click(
      await screen.findByRole('button', { name: 'See history (1)' })
    );
    expect(await screen.findByText('Release?')).toBeVisible();
  });

  it('says an ended run is caught up even with no history, and has no node hint', async () => {
    panel(false);
    expect(await screen.findByText('All caught up')).toBeVisible();
    expect(screen.queryByRole('button', { name: /See history/ })).toBeNull();
  });

  it('shows who answered, how long it took and when', async () => {
    setMe({ ...viewer, keyName: 'alice' } as Me);
    history = [
      answered(),
      answered({
        id: 'h2',
        answer: { ...ANSWER, responderId: 'anonymous' },
      }),
      answered({
        id: 'h3',
        answer: {
          ...ANSWER,
          responderType: 'AUTO',
          responderId: 'guard',
          decision: 'reject',
        },
      }),
      answered({
        id: 'h4',
        kind: 'TEXT',
        answer: { ...ANSWER, decision: 'answer', text: 'Fix it' },
      }),
      answered({
        id: 'h5',
        kind: 'TEXT',
        answer: { ...ANSWER, decision: 'answer' },
      }),
      answered({ id: 'h6', state: 'EXPIRED', answer: null }),
      answered({ id: 'h7', state: 'CANCELLED', answer: null }),
      answered({ id: 'h8', answer: null }),
    ];
    panel();
    await openHistory();
    expect((await screen.findAllByText(/You ·/)).length).toBeGreaterThan(0);
    expect(screen.getByText(/Anonymous \(no API key\)/)).toBeVisible();
    expect(screen.getByText(/guard \(supervisor\)/)).toBeVisible();
    expect(screen.getByText('Rejected')).toBeVisible();
    expect(screen.getByText('“Fix it”')).toBeVisible();
    expect(screen.getByText('No answer in time')).toBeVisible();
    expect(screen.getByText('Cancelled before an answer')).toBeVisible();
    expect(screen.getAllByText(/took 1m 2s/).length).toBeGreaterThan(0);
    const states = screen
      .getAllByTestId('input-state')
      .map((el) => el.getAttribute('data-status'));
    expect(states).toContain('EXPIRED');
  });

  it('groups the history by run when there are several', async () => {
    history = [
      answered({ id: 'a', executionId: 'exec-current-1' }),
      answered({ id: 'b', executionId: 'exec-old-0002' }),
      answered({ id: 'c', executionId: 'exec-old-0002' }),
    ];
    renderWithProviders(
      <InputsPanel
        sessionId="s1"
        workflowId="wf"
        currentExecutionId="exec-current-1"
      />
    );
    await openHistory();
    expect(await screen.findByText(/This run · exec-cur/)).toBeVisible();
    expect(screen.getByText(/^Run · exec-old/)).toBeVisible();
  });

  it('shows no group header for a single run', async () => {
    history = [answered()];
    panel();
    await openHistory();
    await screen.findByText('Release?');
    expect(screen.queryByText(/^(This run|Run) ·/)).toBeNull();
  });

  it('announces a request that arrives while the history is open', async () => {
    const queryClient = createTestQueryClient();
    renderWithProviders(<InputsPanel sessionId="s1" workflowId="wf" />, {
      queryClient,
    });
    await openHistory();
    open = [request()];
    await queryClient.invalidateQueries({ queryKey: ['inputs'] });
    const hint = await screen.findByRole('button', {
      name: 'New request waiting',
    });
    expect(screen.getByRole('status')).toHaveTextContent(
      '1 input request waiting'
    );
    await userEvent.click(hint);
    expect(await screen.findByText('Deploy to production?')).toBeVisible();
    expect(screen.queryByText('New request waiting')).toBeNull();
    open = [request(), request({ id: 'r2' })];
    await queryClient.invalidateQueries({ queryKey: ['inputs'] });
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent(
        '2 input requests waiting'
      )
    );
    expect(screen.queryByText('New request waiting')).toBeNull();
  });

  it('shows the context as a list, and no fold when it is empty', async () => {
    open = [
      request({
        context: { env: 'prod', n: 3, nested: { a: 1 }, nothing: null },
      }),
    ];
    panel();
    await userEvent.click(await screen.findByText('Context (4)'));
    expect(screen.getByText('prod')).toBeVisible();
    expect(screen.getByText('3')).toBeVisible();
    expect(screen.getByText('null')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Copy nested' })).toBeVisible();
  });

  it('has no context fold without context', async () => {
    open = [request()];
    panel();
    await screen.findByText('Deploy to production?');
    expect(screen.queryByText(/^Context/)).toBeNull();
  });

  it('names the asking node, links it, and says who is asked', async () => {
    const onNode = vi.fn();
    open = [request({ createdAt: new Date().toISOString() })];
    renderWithProviders(
      <InputsPanel sessionId="s1" workflowId="wf" onNode={onNode} />
    );
    await userEvent.click(await screen.findByRole('button', { name: 'gate' }));
    expect(onNode).toHaveBeenCalledWith('gate');
    expect(screen.getByText(/for: you/)).toBeVisible();
  });

  it('says which tier a viewer is waiting on', async () => {
    setMe(viewer);
    open = [request({ tier: 'default' })];
    panel();
    expect(
      await screen.findByText(/for: anyone allowed to answer/)
    ).toBeVisible();
  });

  it('shows the risk in words, and advice as a note', async () => {
    open = [request({ risk: 'HIGH', advice: 'be careful' })];
    panel();
    expect(await screen.findByText('High risk · needs a person')).toBeVisible();
    expect(screen.getByText('Advice from the previous approver')).toBeVisible();
    expect(screen.getByText('be careful')).toBeVisible();
  });

  it('flags an escalated request, and the bar shows the time left', async () => {
    const now = Date.now();
    open = [
      request({
        state: 'ESCALATED',
        createdAt: new Date(now - 10_000).toISOString(),
        deadline: new Date(now + 100_000).toISOString(),
      }),
    ];
    const { container } = panel();
    const bar = await screen.findByRole('progressbar', {
      name: 'Time left to answer',
    });
    expect(Number(bar.getAttribute('aria-valuenow'))).toBeGreaterThan(80);
    expect(bar.className).toContain('bg-primary');
    expect(container.querySelector('li')?.className).toContain(
      'border-l-warning'
    );
  });

  it('colours the bar by the time left, and says when it is overdue', async () => {
    const now = Date.now();
    const at = (created: number, deadline: number) => [
      request({
        id: 'mid',
        createdAt: new Date(now - created).toISOString(),
        deadline: new Date(now + deadline).toISOString(),
      }),
      request({
        id: 'low',
        createdAt: new Date(now - created).toISOString(),
        deadline: new Date(now + deadline / 5).toISOString(),
      }),
      request({
        id: 'late',
        createdAt: new Date(now - 100_000).toISOString(),
        deadline: new Date(now - 1000).toISOString(),
      }),
    ];
    open = at(70_000, 30_000);
    panel();
    const bars = await screen.findAllByRole('progressbar');
    expect(bars[0].className).toContain('bg-warning');
    expect(bars[1].className).toContain('bg-destructive');
    expect(bars[2].getAttribute('aria-valuenow')).toBe('0');
    expect(
      screen.getByText('Overdue, the timeout action applies now')
    ).toBeInTheDocument();
  });

  it('sends a text answer with Ctrl+Enter, and not with Enter alone', async () => {
    open = [request({ kind: 'TEXT' })];
    panel();
    const box = await screen.findByLabelText('Answer');
    await userEvent.type(box, 'because');
    await userEvent.keyboard('{Enter}');
    expect(posts).toEqual([]);
    await userEvent.keyboard('{Control>}{Enter}{/Control}');
    await waitFor(() => expect(posts[0]?.body.text).toContain('because'));
  });

  it('does not send an empty answer with Ctrl+Enter', async () => {
    open = [request({ kind: 'TEXT' })];
    panel();
    await userEvent.click(await screen.findByLabelText('Answer'));
    await userEvent.keyboard('{Control>}{Enter}{/Control}');
    expect(posts).toEqual([]);
  });

  it('explains escalation, focuses the reason and returns focus on cancel', async () => {
    open = [request({ tier: 'operator' })];
    panel();
    const button = await screen.findByRole('button', { name: 'Escalate' });
    expect(
      screen.getByText('Not your call? Pass it to the next approver.')
    ).toBeVisible();
    await userEvent.click(button);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent(/the operator tier/);
    expect(within(dialog).getByLabelText('Reason')).toHaveFocus();
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Cancel' })
    );
    await waitFor(() => expect(button).toHaveFocus());
  });
});
