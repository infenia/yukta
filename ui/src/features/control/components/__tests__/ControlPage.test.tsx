// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, beforeEach, afterAll } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { installMockEventSource } from '@/test/mocks/MockEventSource';
import { ControlPage } from '../ControlPage';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const server = setupServer(
  http.get('/api/supervisors/status', () => ok([])),
  http.get('/api/supervisors', () => ok([])),
  http.get('/api/supervisors/assignments', () => ok([])),
  http.get('/api/supervisors/decisions', () => ok([])),
  http.get('/api/supervisor-engines', () => ok([])),
  http.get('/api/sessions/summaries', () => ok([])),
  http.get('/api/metrics/snapshot', () => ok({ time: 0, values: {} })),
  http.get('/api/metrics/series', () => ok({ range: '1h', series: {} })),
  http.get('/api/metrics/heatmap', () => ok([])),
  http.get('/api/metrics/modules', () => ok([])),
  http.get('/api/metrics/annotations', () => ok([])),
  http.get('/api/metrics/runs/live', () => ok([])),
  http.get('/api/control/audit', () => ok([])),
  http.get('/api/inputs', () => ok([])),
  http.get('/api/workers', () => ok([])),
  http.get('/api/scheduler/limits', () =>
    ok({
      maxConcurrent: 0,
      maxPerWorkflow: 0,
      running: 0,
      queued: 0,
      queueCapacity: 100,
    })
  ),
  http.get('/api/edition', () =>
    ok({ edition: 'PLUS', license: 'x', features: [], source: null })
  )
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterAll(() => server.close());

describe('ControlPage', () => {
  it('keeps the control audit in its own tab', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ControlPage />);
    expect(
      screen.queryByRole('heading', { name: 'Who did what' })
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'audit' }));
    expect(screen.getByRole('tab', { name: 'audit' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(window.location.search).toBe('?tab=audit');
    expect(
      await screen.findByText('No control commands recorded yet.')
    ).toBeInTheDocument();
    expect(screen.queryByTestId('mission-control')).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'overview' }));
    expect(window.location.search).toBe('');
  });

  it('opens the audit for an old ?execution link', () => {
    window.history.pushState({}, '', '/control?execution=run-1');
    renderWithProviders(<ControlPage />);
    expect(screen.getByRole('tab', { name: 'audit' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });
});
