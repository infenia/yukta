// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { CoverageTab } from '../CoverageTab';
import type { AttachmentView } from '../../types';
import { createTestQueryClient } from '@/test/utils/testUtils';

const envelope = (data: unknown, status = 200) =>
  HttpResponse.json(
    { timestamp: 't', status, message: 'ok', data, path: '/' },
    { status }
  );

const attachment = (overrides: Partial<AttachmentView>): AttachmentView => ({
  id: 'a',
  supervisorId: 'guard',
  scope: 'global',
  enabled: true,
  source: 'MANUAL',
  originSessionId: null,
  originWorkflowId: null,
  ...overrides,
});

let attachments: AttachmentView[] = [];

const server = setupServer(
  http.get('/api/supervisors/attachments', () => envelope(attachments))
);

beforeAll(() => server.listen());
afterAll(() => server.close());
afterEach(() => {
  server.resetHandlers();
  attachments = [];
});

const renderTab = () =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <CoverageTab />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('CoverageTab', () => {
  it('shows a loading message until the attachments arrive', async () => {
    renderTab();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(await screen.findByText('Nothing is supervised yet')).toBeInTheDocument();
  });

  it('says what to do when nothing is supervised', async () => {
    renderTab();
    expect(
      await screen.findByRole('link', { name: 'Create supervisor' })
    ).toHaveAttribute('href', '/supervisors/new');
  });

  it('says when the coverage cannot be read', async () => {
    server.use(
      http.get('/api/supervisors/attachments', () => envelope({}, 500))
    );
    renderTab();
    expect(
      await screen.findByText('Failed to load the coverage.')
    ).toBeInTheDocument();
  });

  it('nests the scopes and shows who is attached where, and what is inherited', async () => {
    attachments = [
      attachment({ id: 'a1', supervisorId: 'guard', scope: 'session:s1' }),
      attachment({
        id: 'a2',
        supervisorId: 'rules',
        scope: 'workflow:s1/w1//a.x,b.y',
        enabled: false,
        source: 'DEFINITION',
        originSessionId: 's1',
        originWorkflowId: 'w1',
      }),
      attachment({
        id: 'a3',
        supervisorId: 'sys',
        scope: 'session:s2',
        source: 'SYSTEM',
      }),
    ];
    renderTab();

    expect(await screen.findByText('All sessions and workflows')).toBeInTheDocument();
    expect(screen.getByText('Not supervised')).toBeInTheDocument();
    expect(screen.getByText('s1')).toBeInTheDocument();
    expect(screen.getByText('s2')).toBeInTheDocument();
    expect(screen.getByText('a.x, b.y')).toBeInTheDocument();

    // attached on s1, then shown again, as inherited, under its workflow and section
    const guard = screen.getAllByRole('link', { name: 'guard' });
    expect(guard).toHaveLength(3);
    expect(guard[0]).toHaveAttribute('href', '/supervisors/guard');
    expect(guard[0].getAttribute('title')).not.toContain('inherited');
    expect(guard[1]).toHaveAttribute(
      'title',
      expect.stringContaining('inherited from a wider scope')
    );
    expect(guard[2]).toHaveAttribute(
      'title',
      expect.stringContaining('inherited from a wider scope')
    );

    const rules = screen.getByRole('link', { name: 'rules' });
    expect(rules).toHaveAttribute(
      'title',
      expect.stringContaining('declared in a session or workflow definition')
    );
    expect(rules).toHaveAttribute('title', expect.stringContaining('(paused)'));

    const sys = screen.getByRole('link', { name: 'sys' });
    expect(sys).toHaveAttribute('title', expect.stringContaining('set by the server'));

    const legend = screen.getByText('● green: attached by hand');
    expect(within(legend.parentElement as HTMLElement).getByText(/grey/)).toBeInTheDocument();
  });
});
