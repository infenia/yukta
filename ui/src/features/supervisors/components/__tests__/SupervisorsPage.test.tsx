// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SupervisorsPage } from '../SupervisorsPage';
import { createTestQueryClient } from '@/test/utils/testUtils';

const envelope = (data: unknown) => ({
  timestamp: 't',
  status: 200,
  message: 'ok',
  data,
  path: '/',
});

const summaries = [
  {
    type: 'failure-guard',
    displayName: 'Failure guard',
    category: 'GUARD',
    kind: 'SUPERVISOR',
    icon: 'shield',
  },
  {
    type: 'model-control-plane',
    displayName: 'Model control plane',
    category: 'MODEL',
    kind: 'CONTROL_PLANE',
    icon: null,
  },
  {
    type: 'composite',
    displayName: 'Composite',
    category: 'COMPOSITION',
    kind: 'COMPOSITE',
    icon: 'account_tree',
  },
];

const details = (type: string) => ({
  type,
  displayName: summaries.find((s) => s.type === type)?.displayName,
  description: `About ${type}`,
  usagePattern: type === 'composite' ? '' : 'Use it for tests',
  category: 'GUARD',
  kind: 'SUPERVISOR',
  configSchema: [
    {
      name: 'failures',
      type: 'NUMBER',
      required: true,
      defaultValue: 3,
      options: [],
      description: 'How many failures',
    },
    {
      name: 'nodes',
      type: 'LIST',
      required: false,
      defaultValue: ['a'],
      options: [],
      description: 'Which nodes',
    },
  ],
  commonSchema: [
    {
      name: 'maxRisk',
      type: 'ENUM',
      required: false,
      defaultValue: null,
      options: ['LOW'],
      description: 'Highest risk',
    },
  ],
  defaultAllowedCommands: null,
  exampleSettings: { failures: 3 },
});

const server = setupServer(
  http.get('/api/supervisor-types', () =>
    HttpResponse.json(envelope(summaries))
  ),
  http.get('/api/supervisor-types/:type', ({ params }) =>
    HttpResponse.json(envelope(details(String(params.type))))
  ),
  http.get('/api/supervisors/decisions', () => HttpResponse.json(envelope([]))),
  http.get('/api/supervisors/attachments', () =>
    HttpResponse.json(envelope([]))
  ),
  http.get('/api/supervisors', () =>
    HttpResponse.json(
      envelope([
        { id: 'a', type: 'failure-guard' },
        { id: 'b', type: 'x' },
      ])
    )
  )
);
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function Where() {
  const { search } = useLocation();
  return <output aria-label="location">{search}</output>;
}

const renderPage = (url = '/supervisors?tab=catalog') =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <SupervisorsPage />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('SupervisorsPage', () => {
  it('opens on the coverage tab with the explanation, and the empty state points to the library', async () => {
    renderPage('/supervisors');
    expect(
      screen.getByRole('region', { name: 'How supervision works' })
    ).toBeInTheDocument();
    expect(
      await screen.findByText('Nothing is supervised yet')
    ).toBeInTheDocument();
  });

  it('lists the types by category and shows the first one', async () => {
    renderPage();

    expect(
      await screen.findByRole('heading', { name: 'Guards' })
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Models' })).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Gates' })
    ).not.toBeInTheDocument();
    expect(await screen.findByText('About failure-guard')).toBeInTheDocument();
    expect(await screen.findByText(/1 configured/)).toBeInTheDocument();
    expect(screen.getByText('Use it for tests')).toBeInTheDocument();
    expect(screen.getByText('failures')).toBeInTheDocument();
    expect(screen.getByText('maxRisk')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: /Create supervisor/ })
    ).toHaveAttribute('href', '/supervisors/new?type=failure-guard');
  });

  it('shows another type and keeps it in the URL', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(
      await screen.findByRole('button', { name: /Model control plane/ })
    );

    expect(
      await screen.findByText('About model-control-plane')
    ).toBeInTheDocument();
    expect(screen.getByLabelText('location')).toHaveTextContent(
      'type=model-control-plane'
    );
    await user.click(screen.getByRole('button', { name: /^Composite/ }));
    expect(await screen.findByText('About composite')).toBeInTheDocument();
  });

  it('keeps the tab in the URL', async () => {
    const user = userEvent.setup();
    renderPage('/supervisors?tab=catalog');
    await screen.findByText('About failure-guard');

    await user.click(screen.getByRole('tab', { name: 'Activity' }));

    expect(screen.getByLabelText('location')).toHaveTextContent(
      'tab=decisions'
    );
    expect(await screen.findByText('No decisions yet.')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Coverage' }));
    expect(screen.getByLabelText('location')).not.toHaveTextContent('tab=');
  });

  it('opens on the tab the URL names and ignores an unknown one', async () => {
    renderPage('/supervisors?tab=attachments');
    expect(await screen.findByText('No attachments.')).toBeInTheDocument();
  });

  it('says so when a type cannot be loaded or there are none', async () => {
    server.use(
      http.get('/api/supervisor-types/:type', () =>
        HttpResponse.json({}, { status: 500 })
      )
    );
    renderPage();
    expect(
      await screen.findByText('Failed to load failure-guard.')
    ).toBeInTheDocument();
  });

  it('counts none configured while the supervisors cannot be read', async () => {
    server.use(
      http.get('/api/supervisors', () => HttpResponse.json({}, { status: 500 }))
    );
    renderPage();
    expect((await screen.findAllByText(/0 configured/)).length).toBeGreaterThan(
      0
    );
  });

  it('says so when there are no types', async () => {
    server.use(
      http.get('/api/supervisor-types', () => HttpResponse.json(envelope([])))
    );
    renderPage();
    expect(
      await screen.findByText('No supervisor types are installed.')
    ).toBeInTheDocument();
  });

  it('says so when the catalog cannot be loaded', async () => {
    server.use(
      http.get('/api/supervisor-types', () =>
        HttpResponse.json({}, { status: 500 })
      )
    );
    renderPage();
    await waitFor(() =>
      expect(
        screen.getByText('Failed to load the supervisor types.')
      ).toBeInTheDocument()
    );
  });
});
