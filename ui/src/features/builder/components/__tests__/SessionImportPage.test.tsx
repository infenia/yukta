// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  beforeEach,
  afterAll,
  afterEach,
  vi,
} from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { Route, Routes, useLocation } from 'react-router';
import { renderWithProviders } from '@/test/utils/testUtils';
import { SessionImportPage } from '../SessionImportPage';
import { diffWorkflow, parseSession } from '../../importSession';
import type { DraftWorkflow } from '../../types';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const wf = (overrides: Partial<DraftWorkflow> = {}): DraftWorkflow => ({
  workflowId: 'main',
  description: 'd',
  nodes: [
    { nodeId: 'a', type: 'API_TRIGGER', config: {} },
    { nodeId: 'z', type: 'CONSOLE_TERMINAL', config: {} },
  ],
  edges: [{ source: 'a', target: 'z', sourcePort: null }],
  ...overrides,
});
const session = (
  workflows: Record<string, DraftWorkflow> = { main: wf() }
) => ({
  sessionId: 's1',
  name: 'n',
  description: 'd',
  initiator: 'me',
  tags: {},
  projectPath: '/p',
  workflows,
});

let existing: unknown = null;
let existingStatus = 404;
let validation: { valid: boolean; message: string | null } = {
  valid: true,
  message: null,
};
let validateFails = false;
let saved: unknown;
let saveFails = false;

const server = setupServer(
  http.post('/api/sessions/validate', () =>
    validateFails
      ? HttpResponse.json({ message: 'boom' }, { status: 500 })
      : ok({ ...validation, outputPorts: {} })
  ),
  http.get('/api/sessions/s1/config', () =>
    existing
      ? ok(existing)
      : HttpResponse.json({ message: 'none' }, { status: existingStatus })
  ),
  http.post('/api/sessions', async ({ request }) => {
    saved = await request.json();
    return saveFails
      ? HttpResponse.json({ message: 'refused' }, { status: 409 })
      : ok({});
  })
);

beforeAll(() => server.listen());
// the test router is a BrowserRouter: start every test at the page
beforeEach(() => window.history.pushState({}, '', '/'));
afterEach(() => {
  existing = null;
  existingStatus = 404;
  validation = { valid: true, message: null };
  validateFails = false;
  saved = undefined;
  saveFails = false;
});
afterAll(() => server.close());

function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}
const page = () =>
  renderWithProviders(
    <Routes>
      <Route path="/" element={<SessionImportPage />} />
      <Route path="*" element={<Where />} />
    </Routes>
  );

async function paste(user: ReturnType<typeof userEvent.setup>, text: string) {
  await user.click(screen.getByRole('button', { name: 'Paste JSON' }));
  await user.click(screen.getByLabelText('Paste the JSON'));
  await user.paste(text);
}

describe('parseSession', () => {
  it('reports line and column of bad JSON, and other parse failures', () => {
    expect(parseSession('{\n "a": 1,\n nope')).toEqual({
      error: expect.stringMatching(/line 3, column \d+/),
    });
    expect(parseSession('')).toEqual({
      error: expect.stringContaining('Invalid JSON'),
    });
  });

  it('wants a sessionId and workflows', () => {
    expect(parseSession('null')).toHaveProperty('error');
    expect(parseSession('{"sessionId":"s"}')).toHaveProperty('error');
    expect(parseSession('{"sessionId":"s","workflows":null}')).toHaveProperty(
      'error'
    );
    expect(parseSession('{"sessionId":"s","workflows":{}}')).toHaveProperty(
      'session'
    );
  });
});

describe('diffWorkflow', () => {
  it('lists added, removed and changed nodes and edges', () => {
    const imported = wf({
      description: 'd2',
      nodes: [
        { nodeId: 'a', type: 'API_TRIGGER', config: { x: 1 } },
        { nodeId: 'n', type: 'MAPPER', config: {} },
      ],
      edges: [{ source: 'a', target: 'n', sourcePort: 'out' }],
    });
    expect(diffWorkflow(wf(), imported)).toEqual([
      'description changed',
      '+ node n',
      '- node z',
      '~ node a',
      '+ edge a:out -> n',
      '- edge a -> z',
    ]);
    expect(diffWorkflow(wf(), wf())).toEqual([]);
    expect(diffWorkflow(undefined, wf())).toEqual(['new workflow']);
  });
});

describe('SessionImportPage', () => {
  it('validates the JSON in the page as it is pasted', async () => {
    const user = userEvent.setup();
    page();
    await user.click(screen.getByRole('button', { name: 'Paste JSON' }));
    await user.click(screen.getByLabelText('Paste the JSON'));
    await user.paste('{nope');
    expect(screen.getByRole('alert')).toHaveTextContent('Invalid JSON');
    await user.clear(screen.getByLabelText('Paste the JSON'));
    await user.paste(JSON.stringify(session()));
    expect(screen.getByLabelText('Paste the JSON')).toHaveAttribute(
      'aria-invalid',
      'false'
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('rejects JSON that is not a session before calling the server', async () => {
    const user = userEvent.setup();
    page();
    await paste(user, '{nope');
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid JSON');
    expect(
      screen.queryByRole('region', { name: 'Review' })
    ).not.toBeInTheDocument();
  });

  it('keeps Import disabled until the JSON is checked, reads a chosen file and can be cancelled', async () => {
    const user = userEvent.setup();
    page();
    expect(
      screen.getByRole('button', { name: 'Import session' })
    ).toBeDisabled();
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute(
      'href',
      '/sessions/new'
    );
    await user.upload(
      screen.getByLabelText('Session file (.json)'),
      new File([JSON.stringify(session())], 's.json', {
        type: 'application/json',
      })
    );
    await user.click(screen.getByRole('button', { name: 'Paste JSON' }));
    expect(screen.getByLabelText('Paste the JSON')).toHaveValue(
      JSON.stringify(session())
    );
    await user.click(screen.getByRole('button', { name: 'Upload a file' }));
    // choosing nothing (a cancelled dialog) changes nothing
    fireEvent.change(screen.getByLabelText('Session file (.json)'), {
      target: { files: [] },
    });
  });

  it('highlights the drop zone while a file is dragged over it, and reads a dropped file', async () => {
    page();
    const zone = screen.getByLabelText('Session file (.json)').closest('label');
    if (!zone) throw new Error('drop zone not found');
    fireEvent.dragOver(zone);
    expect(zone).toHaveClass('border-primary');
    fireEvent.dragLeave(zone);
    expect(zone).not.toHaveClass('border-primary');
    fireEvent.drop(zone, {
      dataTransfer: {
        files: [
          new File([JSON.stringify(session())], 'dropped.json', {
            type: 'application/json',
          }),
        ],
      },
    });
    expect(await screen.findByText('dropped.json')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Paste JSON' }));
    expect(screen.getByLabelText('Paste the JSON')).toHaveValue(
      JSON.stringify(session())
    );
  });

  it('shows the workflow the server finds invalid, and a failing validation call', async () => {
    validation = { valid: false, message: 'a node is missing' };
    const user = userEvent.setup();
    page();
    await paste(user, JSON.stringify(session()));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'main: a node is missing'
    );

    validateFails = true;
    await user.clear(screen.getByLabelText('Paste the JSON'));
    await user.paste(
      JSON.stringify(session({ main: wf({ description: 'x' }) }))
    );
    expect(await screen.findByText(/main: boom/)).toBeInTheDocument();
    expect(saved).toBeUndefined();
  });

  it('ignores a check that an edit has overtaken', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let calls = 0;
    server.use(
      http.post('/api/sessions/validate', async () => {
        calls += 1;
        if (calls === 1) {
          await gate;
          return ok({ valid: false, message: 'stale', outputPorts: {} });
        }
        return ok({ valid: true, message: null, outputPorts: {} });
      })
    );
    const user = userEvent.setup();
    page();
    await paste(user, JSON.stringify(session()));
    await vi.waitFor(() => expect(calls).toBe(1));

    await user.clear(screen.getByLabelText('Paste the JSON'));
    await user.paste(
      JSON.stringify(session({ main: wf({ description: 'x' }) }))
    );
    expect(
      await screen.findByText(/New session s1 with 1 workflow/)
    ).toBeInTheDocument();
    release();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByText(/stale/)).toBeNull();
  });

  it('imports a new session and opens it; redaction markers get a warning', async () => {
    const user = userEvent.setup();
    page();
    const withSecret = session({
      main: wf({
        nodes: [
          { nodeId: 'a', type: 'HTTP_REQUEST', config: { key: '******' } },
        ],
      }),
    });
    await paste(user, JSON.stringify(withSecret));

    expect(
      await screen.findByText(/New session s1 with 1 workflow/)
    ).toBeInTheDocument();
    expect(screen.getByText(/Secrets are redacted/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Import session' }));

    expect(await screen.findByTestId('where')).toHaveTextContent(
      '/sessions/s1'
    );
    expect(saved).toEqual(withSecret);
  });

  it('shows what an import changes in an existing session, and warns it replaces it', async () => {
    existing = session({ main: wf(), old: wf({ workflowId: 'old' }) });
    const user = userEvent.setup();
    page();
    await paste(
      user,
      JSON.stringify(
        session({ main: wf(), added: wf({ workflowId: 'added' }) })
      )
    );

    expect(await screen.findByText(/Import replaces it/)).toBeInTheDocument();
    expect(screen.getByText('no changes')).toBeInTheDocument();
    expect(screen.getByText('new workflow')).toBeInTheDocument();
    expect(screen.queryByText(/Secrets are redacted/)).not.toBeInTheDocument();

    saveFails = true;
    await user.click(screen.getByRole('button', { name: 'Replace session' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('refused');
  });

  it('stops when the existing session cannot be read for another reason than not found', async () => {
    existingStatus = 500;
    const user = userEvent.setup();
    page();
    await paste(user, JSON.stringify(session()));
    expect(await screen.findByRole('alert')).toHaveTextContent('none');
    expect(
      screen.queryByRole('region', { name: 'Review' })
    ).not.toBeInTheDocument();
  });
});
