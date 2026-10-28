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
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { SessionDetailsPage } from '../SessionDetailsPage';
import { exportSessionConfig } from '../../lib/exportSession';

vi.mock('react-router', async () => ({
  ...(await vi.importActual('react-router')),
  useParams: () => ({ sessionId: 'sess-123' }),
}));
vi.mock('../../lib/exportSession', async () => ({
  ...(await vi.importActual<object>('../../lib/exportSession')),
  exportSessionConfig: vi.fn(),
}));

const server = setupServer(
  http.get('/api/sessions/sess-123', () =>
    HttpResponse.json({
      status: 200,
      data: {
        sessionId: 'sess-123',
        name: 'Test Session',
        description: 'Desc',
        initiator: 'user1',
        tags: [],
        projectPath: '/path',
        workflowIds: [],
      },
    })
  ),
  http.get('/api/sessions/sess-123/workflows', () =>
    HttpResponse.json({ status: 200, data: [] })
  )
);

beforeAll(() => server.listen());
afterEach(() => vi.clearAllMocks());
afterAll(() => server.close());

function renderPage() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <SessionDetailsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('SessionDetailsPage export', () => {
  it('exports the session config and explains that secrets are left out', async () => {
    vi.mocked(exportSessionConfig).mockResolvedValue();
    const user = userEvent.setup();
    renderPage();
    const button = await screen.findByRole('button', { name: /export json/i });
    expect(button).toHaveAttribute('title', 'Secret values are not exported.');
    await user.click(button);
    expect(exportSessionConfig).toHaveBeenCalledWith('sess-123');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows why an export failed, and a generic message for a non-Error', async () => {
    vi.mocked(exportSessionConfig).mockRejectedValueOnce(new Error('gone'));
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: /export json/i })
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('gone');

    vi.mocked(exportSessionConfig).mockRejectedValueOnce('boom');
    await user.click(screen.getByRole('button', { name: /export json/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Export failed');
  });
});
