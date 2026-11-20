// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { limitText, parseLimit } from '../../api/scheduler';
import { LimitsDialog } from '../LimitsDialog';

const limits = {
  maxConcurrent: 4,
  maxPerWorkflow: 0,
  running: 3,
  queued: 1,
  queueCapacity: 100,
};
let postStatus = 200;
const posts: string[] = [];

const server = setupServer(
  http.post('/api/scheduler/limits', ({ request }) => {
    posts.push(new URL(request.url).search);
    return postStatus === 200
      ? HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          data: {},
          path: '/',
        })
      : HttpResponse.json({ message: 'refused' }, { status: postStatus });
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  postStatus = 200;
  posts.length = 0;
});
afterAll(() => server.close());

const dialog = (onClose = vi.fn()) => {
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <LimitsDialog limits={limits} onClose={onClose} />
    </QueryClientProvider>
  );
  return onClose;
};

describe('limit helpers', () => {
  it('reads 0 as unlimited and accepts only whole numbers', () => {
    expect(limitText(0)).toBe('Unlimited');
    expect(limitText(4)).toBe('4');
    expect(parseLimit(' 12 ')).toBe(12);
    expect(parseLimit('0')).toBe(0);
    for (const bad of ['', '-1', '1.5', 'x'])
      expect(parseLimit(bad)).toBeNull();
  });
});

describe('LimitsDialog', () => {
  it('validates, asks to confirm, saves and closes', async () => {
    const user = userEvent.setup();
    const onClose = dialog();
    const concurrent = screen.getByLabelText(/Max concurrent/);
    expect(concurrent).toHaveValue('4');

    await user.clear(concurrent);
    await user.type(concurrent, 'x');
    expect(
      screen.getByText('Enter whole numbers, 0 or more.')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await user.clear(concurrent);
    await user.type(concurrent, '8');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(
      screen.getByText(
        /Set max concurrent to 8 and max per workflow to Unlimited\?/
      )
    ).toBeInTheDocument();
    expect(posts).toEqual([]);

    // changing a value withdraws the confirmation
    await user.type(screen.getByLabelText(/Max per workflow/), '2');
    expect(screen.queryByText(/Set max concurrent/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await user.type(concurrent, '0');
    expect(screen.queryByText(/Set max concurrent/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await user.click(screen.getByRole('button', { name: 'Confirm and save' }));

    await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(posts).toEqual(['?maxConcurrent=80&maxPerWorkflow=2']);
  });

  it('says an ADMIN key is needed on a 403, and shows other failures', async () => {
    const user = userEvent.setup();
    const onClose = dialog();
    await user.click(screen.getByRole('button', { name: 'Save' }));

    postStatus = 403;
    await user.click(screen.getByRole('button', { name: 'Confirm and save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Changing limits needs an ADMIN key.'
    );

    postStatus = 409;
    await user.click(screen.getByRole('button', { name: 'Confirm and save' }));
    expect(await screen.findByText('refused')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes on Cancel without saving', async () => {
    const user = userEvent.setup();
    const onClose = dialog();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(posts).toEqual([]);
  });
});
