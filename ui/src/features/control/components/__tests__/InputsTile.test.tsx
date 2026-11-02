// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { InputsTile } from '../InputsTile';

let items: unknown[] = [];
const server = setupServer(
  http.get('/api/inputs', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: items,
      path: '/',
    })
  )
);
beforeAll(() => server.listen());
afterAll(() => server.close());

describe('InputsTile', () => {
  it('counts the inputs that wait and links to the list', async () => {
    items = [
      { createdAt: new Date(Date.now() - 600_000).toISOString() },
      { createdAt: new Date(Date.now() - 60_000).toISOString() },
    ];
    renderWithProviders(<InputsTile />);
    const link = screen.getByRole('link', { name: 'Inputs' });
    expect(
      await screen.findByText(/oldest 10 minutes ago/)
    ).toBeInTheDocument();
    expect(link).toHaveTextContent('2');
    expect(link).toHaveAttribute('href', '/#ov-needs-attention-panel');
  });

  it('says when none wait', async () => {
    items = [];
    renderWithProviders(<InputsTile />);
    expect(await screen.findByText('none waiting')).toBeInTheDocument();
  });
});
