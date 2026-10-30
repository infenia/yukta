// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { createMockSession } from '@/test/factories/sessionFactory';
import { SessionListPage } from '../SessionListPage';

const sessions = [
  createMockSession({
    sessionId: 's1',
    name: 'Alpha',
    initiator: 'alice',
    tags: ['env=prod', 'team=a'],
    lastRun: { executionId: 'e', workflowId: 'wf', status: 'FAILURE', startTime: '2026-10-30T08:00:00', endTime: null },
  }),
  createMockSession({
    sessionId: 's2',
    name: 'Bravo',
    initiator: 'bob',
    tags: ['env=prod'],
    lastRun: { executionId: 'e', workflowId: 'wf', status: 'RUNNING', startTime: '2026-10-30T08:00:00', endTime: null },
  }),
  createMockSession({
    sessionId: 's3',
    name: 'Charlie',
    initiator: 'alice',
    tags: ['env=dev'],
  }),
];

let data = sessions;

const server = setupServer(
  http.get('/api/sessions/summaries', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data,
      path: '/',
    })
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  data = sessions;
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

const shown = () =>
  ['Alpha', 'Bravo', 'Charlie'].filter(
    (name) => screen.queryByText(name) !== null
  );

async function pick(
  user: ReturnType<typeof userEvent.setup>,
  filter: RegExp,
  option: string
) {
  await user.click(screen.getByRole('button', { name: filter }));
  await user.click(
    await screen.findByRole('menuitemcheckbox', { name: option })
  );
  await user.keyboard('{Escape}');
}

describe('SessionListPage Tag and Initiator filters', () => {
  it('offers every distinct tag and initiator of the loaded sessions', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);
    await screen.findByText('Alpha');

    await user.click(screen.getByRole('button', { name: /tag filter/i }));
    const tags = (await screen.findAllByRole('menuitemcheckbox')).map(
      (item) => item.textContent
    );
    expect(tags).toEqual(['env=dev', 'env=prod', 'team=a']);
    await user.keyboard('{Escape}');

    await user.click(screen.getByRole('button', { name: /initiator filter/i }));
    const who = (await screen.findAllByRole('menuitemcheckbox')).map(
      (item) => item.textContent
    );
    expect(who).toEqual(['alice', 'bob']);
  });

  it('requires all selected tags, any selected initiator, and keeps them in the URL', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);
    await screen.findByText('Alpha');

    await pick(user, /tag filter/i, 'env=prod');
    await waitFor(() => expect(shown()).toEqual(['Alpha', 'Bravo']));
    expect(
      screen.getByRole('button', { name: /tag filter/i })
    ).toHaveTextContent('Tag · 1');

    await pick(user, /tag filter/i, 'team=a');
    await waitFor(() => expect(shown()).toEqual(['Alpha']));
    expect(window.location.search).toBe('?tag=env%3Dprod&tag=team%3Da');

    await pick(user, /initiator filter/i, 'bob');
    await waitFor(() => expect(shown()).toEqual([]));
    await pick(user, /initiator filter/i, 'alice');
    await waitFor(() => expect(shown()).toEqual(['Alpha']));
    expect(window.location.search).toContain('initiator=bob&initiator=alice');
  });

  it('filters by the kind of last run, and keeps it in the URL', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);
    await screen.findByText('Alpha');

    await pick(user, /last run filter/i, 'Failed');
    await waitFor(() => expect(shown()).toEqual(['Alpha']));
    await pick(user, /last run filter/i, 'Not run');
    await waitFor(() => expect(shown()).toEqual(['Alpha', 'Charlie']));
    expect(window.location.search).toBe('?lastrun=Failed&lastrun=Not+run');
  });

  it('removes a value when it is unchecked', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);
    await screen.findByText('Alpha');

    await pick(user, /initiator filter/i, 'bob');
    await waitFor(() => expect(shown()).toEqual(['Bravo']));
    await pick(user, /initiator filter/i, 'bob');
    await waitFor(() => expect(shown()).toEqual(['Alpha', 'Bravo', 'Charlie']));
    expect(window.location.search).toBe('');
  });

  it('restores the filters from the URL after a reload', async () => {
    window.history.pushState({}, '', '/?tag=env%3Dprod&initiator=bob');
    renderWithProviders(<SessionListPage />);
    await screen.findByText('Bravo');
    expect(shown()).toEqual(['Bravo']);
    expect(
      screen.getByRole('button', { name: /tag filter/i })
    ).toHaveTextContent('Tag · 1');
    expect(
      screen.getByRole('button', { name: /initiator filter/i })
    ).toHaveTextContent('Initiator · 1');
  });

  it('clears the search and both filters with Reset', async () => {
    window.history.pushState({}, '', '/?tag=env%3Ddev');
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);
    await screen.findByText('Charlie');
    expect(shown()).toEqual(['Charlie']);

    await user.click(screen.getByLabelText('Reset filters'));
    await waitFor(() => expect(shown()).toEqual(['Alpha', 'Bravo', 'Charlie']));
    expect(window.location.search).toBe('');
  });

  it('says so when no session has tags', async () => {
    data = sessions.map((session) => ({ ...session, tags: [] }));
    const user = userEvent.setup();
    renderWithProviders(<SessionListPage />);
    await screen.findByText('Alpha');
    await user.click(screen.getByRole('button', { name: /tag filter/i }));
    expect(
      await screen.findByText('None in these sessions')
    ).toBeInTheDocument();
  });
});
